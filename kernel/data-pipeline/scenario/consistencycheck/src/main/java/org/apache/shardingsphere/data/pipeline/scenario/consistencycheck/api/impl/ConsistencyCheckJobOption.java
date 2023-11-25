/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.api.impl;

import com.google.common.base.Strings;
import org.apache.shardingsphere.data.pipeline.common.context.PipelineContextKey;
import org.apache.shardingsphere.data.pipeline.common.job.JobStatus;
import org.apache.shardingsphere.data.pipeline.common.job.progress.ConsistencyCheckJobItemProgress;
import org.apache.shardingsphere.data.pipeline.common.job.progress.yaml.YamlConsistencyCheckJobItemProgressSwapper;
import org.apache.shardingsphere.data.pipeline.common.pojo.ConsistencyCheckJobItemInfo;
import org.apache.shardingsphere.data.pipeline.common.registrycenter.repository.PipelineGovernanceFacade;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.result.TableDataConsistencyCheckResult;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.TableDataConsistencyChecker;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.TableDataConsistencyCheckerFactory;
import org.apache.shardingsphere.data.pipeline.core.exception.data.UnsupportedPipelineDatabaseTypeException;
import org.apache.shardingsphere.data.pipeline.core.exception.job.ConsistencyCheckJobNotFoundException;
import org.apache.shardingsphere.data.pipeline.core.exception.job.UncompletedConsistencyCheckJobExistsException;
import org.apache.shardingsphere.data.pipeline.core.job.PipelineJobIdUtils;
import org.apache.shardingsphere.data.pipeline.core.job.option.PipelineJobOption;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineAPIFactory;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobConfigurationManager;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobItemManager;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobManager;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.ConsistencyCheckJob;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.ConsistencyCheckJobId;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.api.pojo.CreateConsistencyCheckJobParameter;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.config.ConsistencyCheckJobConfiguration;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.config.yaml.YamlConsistencyCheckJobConfiguration;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.config.yaml.YamlConsistencyCheckJobConfigurationSwapper;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.util.ConsistencyCheckSequence;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.infra.database.core.type.DatabaseType;
import org.apache.shardingsphere.infra.exception.core.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.util.datetime.DateTimeFormatterFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Consistency check job option.
 */
public final class ConsistencyCheckJobOption implements PipelineJobOption {
    
    /**
     * Create consistency check configuration and start job.
     *
     * @param param create consistency check job parameter
     * @return job id
     */
    public String createJobAndStart(final CreateConsistencyCheckJobParameter param) {
        String parentJobId = param.getParentJobId();
        PipelineGovernanceFacade governanceFacade = PipelineAPIFactory.getPipelineGovernanceFacade(PipelineJobIdUtils.parseContextKey(parentJobId));
        Optional<String> latestCheckJobId = governanceFacade.getJobFacade().getCheck().getLatestCheckJobId(parentJobId);
        if (latestCheckJobId.isPresent()) {
            Optional<ConsistencyCheckJobItemProgress> progress = new PipelineJobItemManager<ConsistencyCheckJobItemProgress>(getYamlJobItemProgressSwapper()).getProgress(latestCheckJobId.get(), 0);
            ShardingSpherePreconditions.checkState(progress.isPresent() && JobStatus.FINISHED == progress.get().getStatus(),
                    () -> new UncompletedConsistencyCheckJobExistsException(latestCheckJobId.get(), progress.orElse(null)));
        }
        checkPipelineDatabaseType(param);
        PipelineContextKey contextKey = PipelineJobIdUtils.parseContextKey(parentJobId);
        String result = latestCheckJobId.map(optional -> new ConsistencyCheckJobId(contextKey, parentJobId, optional)).orElseGet(() -> new ConsistencyCheckJobId(contextKey, parentJobId)).marshal();
        governanceFacade.getJobFacade().getCheck().persistLatestCheckJobId(parentJobId, result);
        governanceFacade.getJobFacade().getCheck().deleteCheckJobResult(parentJobId, result);
        new PipelineJobManager(this).drop(result);
        new PipelineJobManager(this).start(new YamlConsistencyCheckJobConfigurationSwapper().swapToObject(getYamlConfiguration(result, parentJobId, param)));
        return result;
    }
    
    private void checkPipelineDatabaseType(final CreateConsistencyCheckJobParameter param) {
        Collection<DatabaseType> supportedDatabaseTypes;
        try (TableDataConsistencyChecker checker = TableDataConsistencyCheckerFactory.newInstance(param.getAlgorithmTypeName(), param.getAlgorithmProps())) {
            supportedDatabaseTypes = checker.getSupportedDatabaseTypes();
        }
        ShardingSpherePreconditions.checkState(supportedDatabaseTypes.contains(param.getSourceDatabaseType()), () -> new UnsupportedPipelineDatabaseTypeException(param.getSourceDatabaseType()));
        ShardingSpherePreconditions.checkState(supportedDatabaseTypes.contains(param.getTargetDatabaseType()), () -> new UnsupportedPipelineDatabaseTypeException(param.getTargetDatabaseType()));
    }
    
    private YamlConsistencyCheckJobConfiguration getYamlConfiguration(final String jobId, final String parentJobId, final CreateConsistencyCheckJobParameter param) {
        YamlConsistencyCheckJobConfiguration result = new YamlConsistencyCheckJobConfiguration();
        result.setJobId(jobId);
        result.setParentJobId(parentJobId);
        result.setAlgorithmTypeName(param.getAlgorithmTypeName());
        result.setAlgorithmProps(param.getAlgorithmProps());
        result.setSourceDatabaseType(param.getSourceDatabaseType().getType());
        return result;
    }
    
    @Override
    public boolean isIgnoreToStartDisabledJobWhenJobItemProgressIsFinished() {
        return true;
    }
    
    /**
     * Start by parent job id.
     *
     * @param parentJobId parent job id
     */
    public void startByParentJobId(final String parentJobId) {
        new PipelineJobManager(this).startDisabledJob(getLatestCheckJobId(parentJobId));
    }
    
    private String getLatestCheckJobId(final String parentJobId) {
        Optional<String> result = PipelineAPIFactory.getPipelineGovernanceFacade(PipelineJobIdUtils.parseContextKey(parentJobId)).getJobFacade().getCheck().getLatestCheckJobId(parentJobId);
        ShardingSpherePreconditions.checkState(result.isPresent(), () -> new ConsistencyCheckJobNotFoundException(parentJobId));
        return result.get();
    }
    
    /**
     * Start by parent job id.
     *
     * @param parentJobId parent job id
     */
    public void stopByParentJobId(final String parentJobId) {
        new PipelineJobManager(this).stop(getLatestCheckJobId(parentJobId));
    }
    
    /**
     * Drop by parent job id.
     *
     * @param parentJobId parent job id
     */
    public void dropByParentJobId(final String parentJobId) {
        String latestCheckJobId = getLatestCheckJobId(parentJobId);
        new PipelineJobManager(this).stop(latestCheckJobId);
        PipelineContextKey contextKey = PipelineJobIdUtils.parseContextKey(parentJobId);
        PipelineGovernanceFacade governanceFacade = PipelineAPIFactory.getPipelineGovernanceFacade(contextKey);
        Collection<String> checkJobIds = governanceFacade.getJobFacade().getCheck().listCheckJobIds(parentJobId);
        Optional<Integer> previousSequence = ConsistencyCheckSequence.getPreviousSequence(
                checkJobIds.stream().map(ConsistencyCheckJobId::parseSequence).collect(Collectors.toList()), ConsistencyCheckJobId.parseSequence(latestCheckJobId));
        if (previousSequence.isPresent()) {
            String checkJobId = new ConsistencyCheckJobId(contextKey, parentJobId, previousSequence.get()).marshal();
            governanceFacade.getJobFacade().getCheck().persistLatestCheckJobId(parentJobId, checkJobId);
        } else {
            governanceFacade.getJobFacade().getCheck().deleteLatestCheckJobId(parentJobId);
        }
        governanceFacade.getJobFacade().getCheck().deleteCheckJobResult(parentJobId, latestCheckJobId);
        new PipelineJobManager(this).drop(latestCheckJobId);
    }
    
    /**
     * Get consistency job item infos.
     *
     * @param parentJobId parent job id
     * @return consistency job item infos
     */
    public List<ConsistencyCheckJobItemInfo> getJobItemInfos(final String parentJobId) {
        PipelineGovernanceFacade governanceFacade = PipelineAPIFactory.getPipelineGovernanceFacade(PipelineJobIdUtils.parseContextKey(parentJobId));
        Optional<String> latestCheckJobId = governanceFacade.getJobFacade().getCheck().getLatestCheckJobId(parentJobId);
        ShardingSpherePreconditions.checkState(latestCheckJobId.isPresent(), () -> new ConsistencyCheckJobNotFoundException(parentJobId));
        String checkJobId = latestCheckJobId.get();
        PipelineJobItemManager<ConsistencyCheckJobItemProgress> jobItemManager = new PipelineJobItemManager<>(getYamlJobItemProgressSwapper());
        Optional<ConsistencyCheckJobItemProgress> progress = jobItemManager.getProgress(checkJobId, 0);
        if (!progress.isPresent()) {
            return Collections.emptyList();
        }
        List<ConsistencyCheckJobItemInfo> result = new LinkedList<>();
        ConsistencyCheckJobItemProgress jobItemProgress = progress.get();
        if (!Strings.isNullOrEmpty(jobItemProgress.getIgnoredTableNames())) {
            Map<String, TableDataConsistencyCheckResult> checkJobResult = governanceFacade.getJobFacade().getCheck().getCheckJobResult(parentJobId, latestCheckJobId.get());
            result.addAll(buildIgnoredTableInfo(jobItemProgress.getIgnoredTableNames().split(","), checkJobResult));
        }
        if (Objects.equals(jobItemProgress.getIgnoredTableNames(), jobItemProgress.getTableNames())) {
            return result;
        }
        result.add(getJobItemInfo(parentJobId));
        return result;
    }
    
    private List<ConsistencyCheckJobItemInfo> buildIgnoredTableInfo(final String[] ignoredTables, final Map<String, TableDataConsistencyCheckResult> checkJobResult) {
        if (null == ignoredTables) {
            return Collections.emptyList();
        }
        List<ConsistencyCheckJobItemInfo> result = new LinkedList<>();
        for (String each : ignoredTables) {
            ConsistencyCheckJobItemInfo info = new ConsistencyCheckJobItemInfo();
            info.setTableNames(each);
            info.setCheckSuccess(null);
            TableDataConsistencyCheckResult checkResult = checkJobResult.get(each);
            if (null != checkResult && checkResult.isIgnored()) {
                info.setErrorMessage(checkResult.getIgnoredType().getMessage());
            }
            result.add(info);
        }
        return result;
    }
    
    private ConsistencyCheckJobItemInfo getJobItemInfo(final String parentJobId) {
        PipelineGovernanceFacade governanceFacade = PipelineAPIFactory.getPipelineGovernanceFacade(PipelineJobIdUtils.parseContextKey(parentJobId));
        Optional<String> latestCheckJobId = governanceFacade.getJobFacade().getCheck().getLatestCheckJobId(parentJobId);
        ShardingSpherePreconditions.checkState(latestCheckJobId.isPresent(), () -> new ConsistencyCheckJobNotFoundException(parentJobId));
        String checkJobId = latestCheckJobId.get();
        PipelineJobItemManager<ConsistencyCheckJobItemProgress> jobItemManager = new PipelineJobItemManager<>(getYamlJobItemProgressSwapper());
        Optional<ConsistencyCheckJobItemProgress> progress = jobItemManager.getProgress(checkJobId, 0);
        ConsistencyCheckJobItemInfo result = new ConsistencyCheckJobItemInfo();
        JobConfigurationPOJO jobConfigPOJO = PipelineJobIdUtils.getElasticJobConfigurationPOJO(checkJobId);
        result.setActive(!jobConfigPOJO.isDisabled());
        if (!progress.isPresent()) {
            return result;
        }
        ConsistencyCheckJobItemProgress jobItemProgress = progress.get();
        if (null == jobItemProgress.getRecordsCount() || null == jobItemProgress.getCheckedRecordsCount()) {
            result.setInventoryFinishedPercentage(0);
            result.setCheckSuccess(null);
            return result;
        }
        fillInJobItemInfoWithTimes(result, jobItemProgress, jobConfigPOJO);
        result.setTableNames(Optional.ofNullable(jobItemProgress.getTableNames()).orElse(""));
        fillInJobItemInfoWithCheckAlgorithm(result, checkJobId);
        result.setErrorMessage(PipelineAPIFactory.getPipelineGovernanceFacade(PipelineJobIdUtils.parseContextKey(checkJobId)).getJobItemFacade().getErrorMessage().load(checkJobId, 0));
        Map<String, TableDataConsistencyCheckResult> checkJobResults = governanceFacade.getJobFacade().getCheck().getCheckJobResult(parentJobId, checkJobId);
        result.setCheckSuccess(checkJobResults.isEmpty() ? null : checkJobResults.values().stream().allMatch(TableDataConsistencyCheckResult::isMatched));
        result.setCheckFailedTableNames(checkJobResults.entrySet().stream().filter(each -> !each.getValue().isIgnored() && !each.getValue().isMatched())
                .map(Entry::getKey).collect(Collectors.joining(",")));
        return result;
    }
    
    private void fillInJobItemInfoWithTimes(final ConsistencyCheckJobItemInfo result, final ConsistencyCheckJobItemProgress jobItemProgress, final JobConfigurationPOJO jobConfigPOJO) {
        long recordsCount = jobItemProgress.getRecordsCount();
        long checkedRecordsCount = Math.min(jobItemProgress.getCheckedRecordsCount(), recordsCount);
        LocalDateTime checkBeginTime = new Timestamp(jobItemProgress.getCheckBeginTimeMillis()).toLocalDateTime();
        result.setCheckBeginTime(DateTimeFormatterFactory.getLongMillsFormatter().format(checkBeginTime));
        if (JobStatus.FINISHED == jobItemProgress.getStatus()) {
            result.setInventoryFinishedPercentage(100);
            LocalDateTime checkEndTime = new Timestamp(jobItemProgress.getCheckEndTimeMillis()).toLocalDateTime();
            Duration duration = Duration.between(checkBeginTime, checkEndTime);
            result.setDurationSeconds(duration.getSeconds());
            result.setCheckEndTime(DateTimeFormatterFactory.getLongMillsFormatter().format(checkEndTime));
            result.setInventoryRemainingSeconds(0L);
        } else if (0 != recordsCount && 0 != checkedRecordsCount) {
            result.setInventoryFinishedPercentage((int) (checkedRecordsCount * 100 / recordsCount));
            Long stopTimeMillis = jobConfigPOJO.isDisabled() ? Long.parseLong(jobConfigPOJO.getProps().getProperty("stop_time_millis")) : null;
            long durationMillis = (null != stopTimeMillis ? stopTimeMillis : System.currentTimeMillis()) - jobItemProgress.getCheckBeginTimeMillis();
            result.setDurationSeconds(TimeUnit.MILLISECONDS.toSeconds(durationMillis));
            if (null != stopTimeMillis) {
                result.setCheckEndTime(DateTimeFormatterFactory.getLongMillsFormatter().format(new Timestamp(stopTimeMillis).toLocalDateTime()));
            }
            long remainingMills = Math.max(0, (long) ((recordsCount - checkedRecordsCount) * 1.0D / checkedRecordsCount * durationMillis));
            result.setInventoryRemainingSeconds(remainingMills / 1000);
        }
    }
    
    private void fillInJobItemInfoWithCheckAlgorithm(final ConsistencyCheckJobItemInfo result, final String checkJobId) {
        ConsistencyCheckJobConfiguration jobConfig = new PipelineJobConfigurationManager(this).getJobConfiguration(checkJobId);
        result.setAlgorithmType(jobConfig.getAlgorithmTypeName());
        if (null != jobConfig.getAlgorithmProps()) {
            result.setAlgorithmProps(jobConfig.getAlgorithmProps().entrySet().stream().map(entry -> String.format("'%s'='%s'", entry.getKey(), entry.getValue())).collect(Collectors.joining(",")));
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public YamlConsistencyCheckJobConfigurationSwapper getYamlJobConfigurationSwapper() {
        return new YamlConsistencyCheckJobConfigurationSwapper();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public YamlConsistencyCheckJobItemProgressSwapper getYamlJobItemProgressSwapper() {
        return new YamlConsistencyCheckJobItemProgressSwapper();
    }
    
    @Override
    public Class<ConsistencyCheckJob> getJobClass() {
        return ConsistencyCheckJob.class;
    }
    
    @Override
    public String getType() {
        return "CONSISTENCY_CHECK";
    }
}
