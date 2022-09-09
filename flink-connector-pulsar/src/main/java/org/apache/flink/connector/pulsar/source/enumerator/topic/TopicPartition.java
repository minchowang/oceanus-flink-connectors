/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.source.enumerator.topic;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;

import org.apache.pulsar.client.api.Range;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.naming.TopicName;

import java.io.Serializable;
import java.util.Objects;

import static org.apache.flink.connector.pulsar.source.enumerator.topic.TopicNameUtils.topicNameWithPartition;
import static org.apache.flink.connector.pulsar.source.enumerator.topic.TopicRange.createFullRange;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Topic partition is the basic topic information used by {@link SplitReader}, we create this topic
 * metas for a specified topic by subscription type and convert it into a partition split.
 */
@PublicEvolving
public class TopicPartition implements Serializable {
    private static final long serialVersionUID = -1474354741550810953L;

    /**
     * If {@link TopicPartition#getPartitionId()} is equal to this. This topic partition wouldn't be
     * a partition instance. It would be a top topic name.
     */
    public static final int NON_PARTITION_ID = -1;

    /**
     * The topic name of the pulsar. It would be a full topic name, if your don't provide the tenant
     * and namespace, we would add them automatically.
     */
    private final String topic;

    /**
     * Index of partition for the topic. It would be natural number for partitioned topic with a
     * non-key_shared subscription.
     */
    private final int partitionId;

    /**
     * The ranges for this topic, used for limiting consume scope. It would be a {@link
     * TopicRange#createFullRange()} full range for all the subscription type except {@link
     * SubscriptionType#Key_Shared}.
     */
    private final TopicRange range;

    /** Create a top-level topic without partition information. */
    public TopicPartition(String topic) {
        TopicName topicName = TopicName.get(topic);
        this.topic = topicName.getPartitionedTopicName();
        this.partitionId =
                topicName.isPartitioned() ? topicName.getPartitionIndex() : NON_PARTITION_ID;
        this.range = createFullRange();
    }

    /** Create a topic partition without key hash range. */
    public TopicPartition(String topic, int partitionId) {
        this(topic, partitionId, createFullRange());
    }

    @Internal
    public TopicPartition(String topic, int partitionId, TopicRange range) {
        TopicName topicName = TopicName.get(topic);

        this.topic = topicName.getPartitionedTopicName();
        this.partitionId =
                partitionId == NON_PARTITION_ID && topicName.isPartitioned()
                        ? topicName.getPartitionIndex()
                        : partitionId;
        this.range = checkNotNull(range);
    }

    public String getTopic() {
        return topic;
    }

    public int getPartitionId() {
        return partitionId;
    }

    /** @return Is this a partition instance or a topic instance? */
    public boolean isPartition() {
        return partitionId != NON_PARTITION_ID;
    }

    /**
     * Pulsar split the topic partition into a bunch of small topics, we would get the real topic
     * name by using this method.
     */
    public String getFullTopicName() {
        if (isPartition()) {
            return topicNameWithPartition(topic, partitionId);
        } else {
            return topic;
        }
    }

    public TopicRange getRange() {
        return range;
    }

    public Range getPulsarRange() {
        return range.toPulsarRange();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TopicPartition that = (TopicPartition) o;
        return partitionId == that.partitionId
                && topic.equals(that.topic)
                && range.equals(that.range);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, partitionId, range);
    }

    @Override
    public String toString() {
        return getFullTopicName() + "|" + range;
    }
}
