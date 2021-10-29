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

package org.apache.shardingsphere.sharding.distsql.handler.preprocess;

import com.google.common.base.Preconditions;
import org.apache.shardingsphere.infra.distsql.preprocess.RuleDefinitionAlterPreprocessor;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingAutoTableRuleConfiguration;
import org.apache.shardingsphere.sharding.distsql.parser.statement.AlterShardingTableRuleStatement;

import java.util.Optional;

/**
 * Alter sharding table rule statement preprocessor.
 */
public final class AlterShardingTableRuleStatementPreprocessor implements RuleDefinitionAlterPreprocessor<AlterShardingTableRuleStatement, ShardingRuleConfiguration> {
    
    @Override
    public String getType() {
        return AlterShardingTableRuleStatement.class.getCanonicalName();
    }
    
    @Override
    public ShardingRuleConfiguration preprocess(final ShardingRuleConfiguration currentRuleConfig, final ShardingRuleConfiguration toBeAlteredRuleConfig) {
        ShardingRuleConfiguration result = new ShardingRuleConfiguration();
        result.setShardingAlgorithms(currentRuleConfig.getShardingAlgorithms());
        result.setAutoTables(currentRuleConfig.getAutoTables());
        result.setDefaultShardingColumn(currentRuleConfig.getDefaultShardingColumn());
        result.setDefaultTableShardingStrategy(currentRuleConfig.getDefaultTableShardingStrategy());
        result.setBindingTableGroups(currentRuleConfig.getBindingTableGroups());
        result.setDefaultDatabaseShardingStrategy(currentRuleConfig.getDefaultDatabaseShardingStrategy());
        result.setTables(currentRuleConfig.getTables());
        result.setBroadcastTables(currentRuleConfig.getBroadcastTables());
        result.setDefaultKeyGenerateStrategy(currentRuleConfig.getDefaultKeyGenerateStrategy());
        result.setKeyGenerators(currentRuleConfig.getKeyGenerators());
        dropRuleConfiguration(result, toBeAlteredRuleConfig);
        addRuleConfiguration(result, toBeAlteredRuleConfig);
        return result;
    }
    
    private void dropRuleConfiguration(final ShardingRuleConfiguration preAlteredRuleConfig, final ShardingRuleConfiguration toBeAlteredRuleConfig) {
        for (ShardingAutoTableRuleConfiguration each : toBeAlteredRuleConfig.getAutoTables()) {
            Optional<ShardingAutoTableRuleConfiguration> shardingAutoTableRuleConfig
                    = preAlteredRuleConfig.getAutoTables().stream().filter(tableRule -> each.getLogicTable().equals(tableRule.getLogicTable())).findAny();
            Preconditions.checkState(shardingAutoTableRuleConfig.isPresent());
            preAlteredRuleConfig.getAutoTables().remove(shardingAutoTableRuleConfig.get());
            preAlteredRuleConfig.getShardingAlgorithms().remove(shardingAutoTableRuleConfig.get().getShardingStrategy().getShardingAlgorithmName());
            if (null != shardingAutoTableRuleConfig.get().getKeyGenerateStrategy()) {
                preAlteredRuleConfig.getKeyGenerators().remove(shardingAutoTableRuleConfig.get().getKeyGenerateStrategy().getKeyGeneratorName());
            }
        }
    }
    
    private void addRuleConfiguration(final ShardingRuleConfiguration currentRuleConfig, final ShardingRuleConfiguration toBeAlteredRuleConfig) {
        currentRuleConfig.getAutoTables().addAll(toBeAlteredRuleConfig.getAutoTables());
        currentRuleConfig.getShardingAlgorithms().putAll(toBeAlteredRuleConfig.getShardingAlgorithms());
        currentRuleConfig.getKeyGenerators().putAll(toBeAlteredRuleConfig.getKeyGenerators());
    }
}
