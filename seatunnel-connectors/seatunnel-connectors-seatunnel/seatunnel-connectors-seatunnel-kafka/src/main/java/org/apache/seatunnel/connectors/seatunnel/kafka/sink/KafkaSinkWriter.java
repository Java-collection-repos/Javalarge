/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.kafka.sink;

import org.apache.seatunnel.api.sink.SinkWriter;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowTypeInfo;
import org.apache.seatunnel.common.config.TypesafeConfigUtils;
import org.apache.seatunnel.connectors.seatunnel.kafka.config.KafkaSemantics;
import org.apache.seatunnel.connectors.seatunnel.kafka.serialize.DefaultSeaTunnelRowSerializer;
import org.apache.seatunnel.connectors.seatunnel.kafka.serialize.SeaTunnelRowSerializer;
import org.apache.seatunnel.connectors.seatunnel.kafka.state.KafkaCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.kafka.state.KafkaState;

import org.apache.seatunnel.shade.com.typesafe.config.Config;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * KafkaSinkWriter is a sink writer that will write {@link SeaTunnelRow} to Kafka.
 */
public class KafkaSinkWriter implements SinkWriter<SeaTunnelRow, KafkaCommitInfo, KafkaState> {

    private final SinkWriter.Context context;
    private SeaTunnelRowTypeInfo seaTunnelRowTypeInfo;
    private final Config pluginConfig;

    private KafkaProduceSender<?, ?> kafkaProducerSender;

    public KafkaSinkWriter(
        SinkWriter.Context context,
        SeaTunnelRowTypeInfo seaTunnelRowTypeInfo,
        Config pluginConfig,
        List<KafkaState> kafkaStates) {
        this.context = context;
        this.seaTunnelRowTypeInfo = seaTunnelRowTypeInfo;
        this.pluginConfig = pluginConfig;
        if (KafkaSemantics.AT_LEAST_ONCE.equals(getKafkaSemantics(pluginConfig))) {
            // the recover state
            this.kafkaProducerSender = new KafkaTransactionSender<>(
                getKafkaProperties(pluginConfig),
                getSerializer(pluginConfig));
            this.kafkaProducerSender.abortTransaction(kafkaStates);
            this.kafkaProducerSender.beginTransaction();
        } else {
            this.kafkaProducerSender = new KafkaNoTransactionSender<>(
                getKafkaProperties(pluginConfig),
                getSerializer(pluginConfig));
        }
    }

    @Override
    public void write(SeaTunnelRow element) {
        kafkaProducerSender.send(element);
    }

    @Override
    public List<KafkaState> snapshotState() {
        return kafkaProducerSender.snapshotState();
    }

    @Override
    public Optional<KafkaCommitInfo> prepareCommit() {
        return kafkaProducerSender.prepareCommit();
    }

    @Override
    public void abort() {
        kafkaProducerSender.abortTransaction();
    }

    @Override
    public void close() {
        try (KafkaProduceSender<?, ?> kafkaProduceSender = kafkaProducerSender) {
            // no-opt
        } catch (Exception e) {
            throw new RuntimeException("Close kafka sink writer error", e);
        }
    }

    private Properties getKafkaProperties(Config pluginConfig) {
        Config kafkaConfig = TypesafeConfigUtils.extractSubConfig(pluginConfig,
            org.apache.seatunnel.connectors.seatunnel.kafka.config.Config.KAFKA_CONFIG_PREFIX, true);
        Properties kafkaProperties = new Properties();
        kafkaConfig.entrySet().forEach(entry -> {
            kafkaProperties.put(entry.getKey(), entry.getValue().unwrapped());
        });
        return kafkaProperties;
    }

    private SeaTunnelRowSerializer<?, ?> getSerializer(Config pluginConfig) {
        return new DefaultSeaTunnelRowSerializer(pluginConfig.getString("topic"));
    }

    private KafkaSemantics getKafkaSemantics(Config pluginConfig) {
        if (pluginConfig.hasPath("semantics")) {
            return pluginConfig.getEnum(KafkaSemantics.class, "semantics");
        }
        return KafkaSemantics.NON;
    }
}