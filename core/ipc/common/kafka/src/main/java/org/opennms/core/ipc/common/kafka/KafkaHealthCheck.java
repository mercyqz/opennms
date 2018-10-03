/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.core.ipc.common.kafka;


import java.util.Properties;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.opennms.core.health.api.HealthCheck;
import org.opennms.core.health.api.Response;
import org.opennms.core.health.api.Status;


public class KafkaHealthCheck implements HealthCheck {

    private OsgiKafkaConfigProvider osgiKafkaConfigProvider;
    private OnmsKafkaConfigProvider kafkaConfigProvider;
    // Differentiate Sink/RPC
    private final String type;
    private Properties kafkaConfig = new Properties();

    public KafkaHealthCheck(OsgiKafkaConfigProvider kafkaConfigProvider, String type) {
        this.osgiKafkaConfigProvider = kafkaConfigProvider;
        this.type = type;
    }

    public KafkaHealthCheck(String sysPropPrefix, String type) {
        this.kafkaConfigProvider = new OnmsKafkaConfigProvider(sysPropPrefix);
        this.type = type;
    }

    @Override
    public String getDescription() {
        return "Connecting to Kafka from " + type ;
    }

    @Override
    public Response perform() throws Exception {
        if (osgiKafkaConfigProvider != null) {
            kafkaConfig = osgiKafkaConfigProvider.getProperties();
        }
        if (kafkaConfigProvider != null) {
            kafkaConfig = kafkaConfigProvider.getProperties();
        }
        // Default 1 sec should be sufficient, need changes to health check API to make it configurable.
        kafkaConfig.put("request.timeout.ms", 1000);
        try (AdminClient client = Utils.runWithNullContextClassLoader(() -> AdminClient.create(kafkaConfig))) {
            ListTopicsResult listTopicsResult = client.listTopics();
            listTopicsResult.names().get();
            return new Response(Status.Success);
        } catch (Exception e) {
            return new Response(e);
        }
    }
}