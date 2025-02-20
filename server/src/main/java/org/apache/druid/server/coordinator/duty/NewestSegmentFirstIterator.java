/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordinator.duty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.ArrayUtils;
import org.apache.druid.client.indexing.ClientCompactionTaskGranularitySpec;
import org.apache.druid.client.indexing.ClientCompactionTaskQueryTuningConfig;
import org.apache.druid.client.indexing.ClientCompactionTaskTransformSpec;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.JodaUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.SegmentUtils;
import org.apache.druid.server.coordinator.CompactionStatistics;
import org.apache.druid.server.coordinator.DataSourceCompactionConfig;
import org.apache.druid.timeline.CompactionState;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.Partitions;
import org.apache.druid.timeline.SegmentTimeline;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.NumberedPartitionChunk;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.utils.Streams;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class iterates all segments of the dataSources configured for compaction from the newest to the oldest.
 */
public class NewestSegmentFirstIterator implements CompactionSegmentIterator
{
  private static final Logger log = new Logger(NewestSegmentFirstIterator.class);

  private final ObjectMapper objectMapper;
  private final Map<String, DataSourceCompactionConfig> compactionConfigs;
  private final Map<String, CompactionStatistics> compactedSegments = new HashMap<>();
  private final Map<String, CompactionStatistics> skippedSegments = new HashMap<>();

  // dataSource -> intervalToFind
  // searchIntervals keeps track of the current state of which interval should be considered to search segments to
  // compact.
  private final Map<String, CompactibleTimelineObjectHolderCursor> timelineIterators;
  // This is needed for datasource that has segmentGranularity configured
  // If configured segmentGranularity in config is finer than current segmentGranularity, the same set of segments
  // can belong to multiple intervals in the timeline. We keep track of the compacted intervals between each
  // run of the compaction job and skip any interval that was already previously compacted.
  private final Map<String, Set<Interval>> intervalCompactedForDatasource = new HashMap<>();

  private final PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
      (o1, o2) -> Comparators.intervalsByStartThenEnd().compare(o2.interval, o1.interval)
  );

  NewestSegmentFirstIterator(
      ObjectMapper objectMapper,
      Map<String, DataSourceCompactionConfig> compactionConfigs,
      Map<String, SegmentTimeline> dataSources,
      Map<String, List<Interval>> skipIntervals
  )
  {
    this.objectMapper = objectMapper;
    this.compactionConfigs = compactionConfigs;
    this.timelineIterators = Maps.newHashMapWithExpectedSize(dataSources.size());

    dataSources.forEach((String dataSource, SegmentTimeline timeline) -> {
      final DataSourceCompactionConfig config = compactionConfigs.get(dataSource);
      Granularity configuredSegmentGranularity = null;
      if (config != null && !timeline.isEmpty()) {
        VersionedIntervalTimeline<String, DataSegment> originalTimeline = null;
        if (config.getGranularitySpec() != null && config.getGranularitySpec().getSegmentGranularity() != null) {
          String temporaryVersion = DateTimes.nowUtc().toString();
          Map<Interval, Set<DataSegment>> intervalToPartitionMap = new HashMap<>();
          configuredSegmentGranularity = config.getGranularitySpec().getSegmentGranularity();
          // Create a new timeline to hold segments in the new configured segment granularity
          SegmentTimeline timelineWithConfiguredSegmentGranularity = new SegmentTimeline();
          Set<DataSegment> segments = timeline.findNonOvershadowedObjectsInInterval(Intervals.ETERNITY, Partitions.ONLY_COMPLETE);
          for (DataSegment segment : segments) {
            // Convert original segmentGranularity to new granularities bucket by configuredSegmentGranularity
            // For example, if the original is interval of 2020-01-28/2020-02-03 with WEEK granularity
            // and the configuredSegmentGranularity is MONTH, the segment will be split to two segments
            // of 2020-01/2020-02 and 2020-02/2020-03.
            for (Interval interval : configuredSegmentGranularity.getIterable(segment.getInterval())) {
              intervalToPartitionMap.computeIfAbsent(interval, k -> new HashSet<>()).add(segment);
            }
          }
          for (Map.Entry<Interval, Set<DataSegment>> partitionsPerInterval : intervalToPartitionMap.entrySet()) {
            Interval interval = partitionsPerInterval.getKey();
            int partitionNum = 0;
            Set<DataSegment> segmentSet = partitionsPerInterval.getValue();
            int partitions = segmentSet.size();
            for (DataSegment segment : segmentSet) {
              DataSegment segmentsForCompact = segment.withShardSpec(new NumberedShardSpec(partitionNum, partitions));
              timelineWithConfiguredSegmentGranularity.add(
                  interval,
                  temporaryVersion,
                  NumberedPartitionChunk.make(partitionNum, partitions, segmentsForCompact)
              );
              partitionNum += 1;
            }
          }
          // PartitionHolder can only holds chunks of one partition space
          // However, partition in the new timeline (timelineWithConfiguredSegmentGranularity) can be hold multiple
          // partitions of the original timeline (when the new segmentGranularity is larger than the original
          // segmentGranularity). Hence, we group all the segments of the original timeline into intervals bucket
          // by the new configuredSegmentGranularity. We then convert each segment into a new partition space so that
          // there is no duplicate partitionNum across all segments of each new Interval.
          // Similarly, segment versions may be mixed in the same time chunk based on new segment granularity
          // Hence we create the new timeline with a temporary version, setting the fake version to all be the same
          // for the same new time bucket.
          // We need to save and store the originalTimeline so that we can use it
          // to get the original ShardSpec and original version back (when converting the segment back to return from this iterator).
          originalTimeline = timeline;
          timeline = timelineWithConfiguredSegmentGranularity;
        }
        final List<Interval> searchIntervals =
            findInitialSearchInterval(dataSource, timeline, config.getSkipOffsetFromLatest(), configuredSegmentGranularity, skipIntervals.get(dataSource));
        if (!searchIntervals.isEmpty()) {
          timelineIterators.put(dataSource, new CompactibleTimelineObjectHolderCursor(timeline, searchIntervals, originalTimeline));
        }
      }
    });

    compactionConfigs.forEach((String dataSourceName, DataSourceCompactionConfig config) -> {
      if (config == null) {
        throw new ISE("Unknown dataSource[%s]", dataSourceName);
      }
      updateQueue(dataSourceName, config);
    });
  }

  @Override
  public Map<String, CompactionStatistics> totalCompactedStatistics()
  {
    return compactedSegments;
  }

  @Override
  public Map<String, CompactionStatistics> totalSkippedStatistics()
  {
    return skippedSegments;
  }

  @Override
  public boolean hasNext()
  {
    return !queue.isEmpty();
  }

  @Override
  public List<DataSegment> next()
  {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    final QueueEntry entry = queue.poll();

    if (entry == null) {
      throw new NoSuchElementException();
    }

    final List<DataSegment> resultSegments = entry.segments;

    Preconditions.checkState(!resultSegments.isEmpty(), "Queue entry must not be empty");

    final String dataSource = resultSegments.get(0).getDataSource();

    updateQueue(dataSource, compactionConfigs.get(dataSource));

    return resultSegments;
  }

  /**
   * Find the next segments to compact for the given dataSource and add them to the queue.
   * {@link #timelineIterators} is updated according to the found segments. That is, the found segments are removed from
   * the timeline of the given dataSource.
   */
  private void updateQueue(String dataSourceName, DataSourceCompactionConfig config)
  {
    final CompactibleTimelineObjectHolderCursor compactibleTimelineObjectHolderCursor = timelineIterators.get(
        dataSourceName
    );

    if (compactibleTimelineObjectHolderCursor == null) {
      log.warn("Cannot find timeline for dataSource[%s]. Skip this dataSource", dataSourceName);
      return;
    }

    final SegmentsToCompact segmentsToCompact = findSegmentsToCompact(
        dataSourceName,
        compactibleTimelineObjectHolderCursor,
        config
    );

    if (!segmentsToCompact.isEmpty()) {
      queue.add(new QueueEntry(segmentsToCompact.segments));
    }
  }

  /**
   * Iterates the given {@link VersionedIntervalTimeline}. Only compactible {@link TimelineObjectHolder}s are returned,
   * which means the holder always has at least one {@link DataSegment}.
   */
  private static class CompactibleTimelineObjectHolderCursor implements Iterator<List<DataSegment>>
  {
    private final List<TimelineObjectHolder<String, DataSegment>> holders;
    @Nullable
    private final VersionedIntervalTimeline<String, DataSegment> originalTimeline;

    CompactibleTimelineObjectHolderCursor(
        VersionedIntervalTimeline<String, DataSegment> timeline,
        List<Interval> totalIntervalsToSearch,
        // originalTimeline can be nullable if timeline was not modified
        @Nullable VersionedIntervalTimeline<String, DataSegment> originalTimeline
    )
    {
      this.holders = totalIntervalsToSearch
          .stream()
          .flatMap(interval -> timeline
              .lookup(interval)
              .stream()
              .filter(holder -> isCompactibleHolder(interval, holder))
          )
          .collect(Collectors.toList());
      this.originalTimeline = originalTimeline;
    }

    private boolean isCompactibleHolder(Interval interval, TimelineObjectHolder<String, DataSegment> holder)
    {
      final Iterator<PartitionChunk<DataSegment>> chunks = holder.getObject().iterator();
      if (!chunks.hasNext()) {
        return false; // There should be at least one chunk for a holder to be compactible.
      }
      PartitionChunk<DataSegment> firstChunk = chunks.next();
      if (!interval.contains(firstChunk.getObject().getInterval())) {
        return false;
      }
      long partitionBytes = firstChunk.getObject().getSize();
      while (partitionBytes == 0 && chunks.hasNext()) {
        partitionBytes += chunks.next().getObject().getSize();
      }
      return partitionBytes > 0;
    }

    @Override
    public boolean hasNext()
    {
      return !holders.isEmpty();
    }

    @Override
    public List<DataSegment> next()
    {
      if (holders.isEmpty()) {
        throw new NoSuchElementException();
      }
      TimelineObjectHolder<String, DataSegment> timelineObjectHolder = holders.remove(holders.size() - 1);
      List<DataSegment> candidates = Streams.sequentialStreamFrom(timelineObjectHolder.getObject())
                                            .map(PartitionChunk::getObject)
                                            .collect(Collectors.toList());
      if (originalTimeline != null) {
        Interval umbrellaInterval = JodaUtils.umbrellaInterval(candidates.stream().map(DataSegment::getInterval).collect(Collectors.toList()));
        return Lists.newArrayList(originalTimeline.findNonOvershadowedObjectsInInterval(umbrellaInterval, Partitions.ONLY_COMPLETE));
      }
      return candidates;
    }
  }

  @VisibleForTesting
  static PartitionsSpec findPartitionsSpecFromConfig(ClientCompactionTaskQueryTuningConfig tuningConfig)
  {
    final PartitionsSpec partitionsSpecFromTuningConfig = tuningConfig.getPartitionsSpec();
    if (partitionsSpecFromTuningConfig instanceof DynamicPartitionsSpec) {
      return new DynamicPartitionsSpec(
          partitionsSpecFromTuningConfig.getMaxRowsPerSegment(),
          ((DynamicPartitionsSpec) partitionsSpecFromTuningConfig).getMaxTotalRowsOr(Long.MAX_VALUE)
      );
    } else {
      final long maxTotalRows = tuningConfig.getMaxTotalRows() != null
                                ? tuningConfig.getMaxTotalRows()
                                : Long.MAX_VALUE;
      return partitionsSpecFromTuningConfig == null
             ? new DynamicPartitionsSpec(tuningConfig.getMaxRowsPerSegment(), maxTotalRows)
             : partitionsSpecFromTuningConfig;
    }
  }

  private boolean needsCompaction(DataSourceCompactionConfig config, SegmentsToCompact candidates)
  {
    Preconditions.checkState(!candidates.isEmpty(), "Empty candidates");
    final ClientCompactionTaskQueryTuningConfig tuningConfig =
        ClientCompactionTaskQueryTuningConfig.from(config.getTuningConfig(), config.getMaxRowsPerSegment(), null);
    final PartitionsSpec partitionsSpecFromConfig = findPartitionsSpecFromConfig(tuningConfig);
    final CompactionState lastCompactionState = candidates.segments.get(0).getLastCompactionState();
    if (lastCompactionState == null) {
      log.info("Candidate segment[%s] is not compacted yet. Needs compaction.", candidates.segments.get(0).getId());
      return true;
    }

    final boolean allCandidatesHaveSameLastCompactionState = candidates
        .segments
        .stream()
        .allMatch(segment -> lastCompactionState.equals(segment.getLastCompactionState()));

    if (!allCandidatesHaveSameLastCompactionState) {
      log.info(
          "[%s] Candidate segments were compacted with different partitions spec. Needs compaction.",
          candidates.segments.size()
      );
      log.debugSegments(
          candidates.segments,
          "Candidate segments compacted with different partiton spec"
      );

      return true;
    }

    final PartitionsSpec segmentPartitionsSpec = lastCompactionState.getPartitionsSpec();
    final IndexSpec segmentIndexSpec = objectMapper.convertValue(lastCompactionState.getIndexSpec(), IndexSpec.class);
    final IndexSpec configuredIndexSpec;
    if (tuningConfig.getIndexSpec() == null) {
      configuredIndexSpec = new IndexSpec();
    } else {
      configuredIndexSpec = tuningConfig.getIndexSpec();
    }
    if (!Objects.equals(partitionsSpecFromConfig, segmentPartitionsSpec)) {
      log.info(
          "Configured partitionsSpec[%s] is differenet from "
          + "the partitionsSpec[%s] of segments. Needs compaction.",
          partitionsSpecFromConfig,
          segmentPartitionsSpec
      );
      return true;
    }
    // segmentIndexSpec cannot be null.
    if (!segmentIndexSpec.equals(configuredIndexSpec)) {
      log.info(
          "Configured indexSpec[%s] is different from the one[%s] of segments. Needs compaction",
          configuredIndexSpec,
          segmentIndexSpec
      );
      return true;
    }

    if (config.getGranularitySpec() != null) {

      final ClientCompactionTaskGranularitySpec existingGranularitySpec = lastCompactionState.getGranularitySpec() != null ?
                                                                          objectMapper.convertValue(lastCompactionState.getGranularitySpec(), ClientCompactionTaskGranularitySpec.class) :
                                                                          null;
      // Checks for segmentGranularity
      if (config.getGranularitySpec().getSegmentGranularity() != null) {
        final Granularity existingSegmentGranularity = existingGranularitySpec != null ?
                                                       existingGranularitySpec.getSegmentGranularity() :
                                                       null;
        if (existingSegmentGranularity == null) {
          // Candidate segments were all compacted without segment granularity set.
          // We need to check if all segments have the same segment granularity as the configured segment granularity.
          boolean needsCompaction = candidates.segments.stream()
                                                       .anyMatch(segment -> !config.getGranularitySpec().getSegmentGranularity().isAligned(segment.getInterval()));
          if (needsCompaction) {
            log.info(
                "Segments were previously compacted but without segmentGranularity in auto compaction."
                + " Configured segmentGranularity[%s] is different from granularity implied by segment intervals. Needs compaction",
                config.getGranularitySpec().getSegmentGranularity()
            );
            return true;
          }

        } else if (!config.getGranularitySpec().getSegmentGranularity().equals(existingSegmentGranularity)) {
          log.info(
              "Configured segmentGranularity[%s] is different from the segmentGranularity[%s] of segments. Needs compaction",
              config.getGranularitySpec().getSegmentGranularity(),
              existingSegmentGranularity
          );
          return true;
        }
      }

      // Checks for rollup
      if (config.getGranularitySpec().isRollup() != null) {
        final Boolean existingRollup = existingGranularitySpec != null ?
                                       existingGranularitySpec.isRollup() :
                                       null;
        if (existingRollup == null || !config.getGranularitySpec().isRollup().equals(existingRollup)) {
          log.info(
              "Configured rollup[%s] is different from the rollup[%s] of segments. Needs compaction",
              config.getGranularitySpec().isRollup(),
              existingRollup
          );
          return true;
        }
      }

      // Checks for queryGranularity
      if (config.getGranularitySpec().getQueryGranularity() != null) {

        final Granularity existingQueryGranularity = existingGranularitySpec != null ?
                                                     existingGranularitySpec.getQueryGranularity() :
                                                     null;
        if (!config.getGranularitySpec().getQueryGranularity().equals(existingQueryGranularity)) {
          log.info(
              "Configured queryGranularity[%s] is different from the queryGranularity[%s] of segments. Needs compaction",
              config.getGranularitySpec().getQueryGranularity(),
              existingQueryGranularity
          );
          return true;
        }
      }
    }

    if (config.getDimensionsSpec() != null) {
      final DimensionsSpec existingDimensionsSpec = lastCompactionState.getDimensionsSpec();
      // Checks for list of dimensions
      if (config.getDimensionsSpec().getDimensions() != null) {
        final List<DimensionSchema> existingDimensions = existingDimensionsSpec != null ?
                                                         existingDimensionsSpec.getDimensions() :
                                                         null;
        if (!config.getDimensionsSpec().getDimensions().equals(existingDimensions)) {
          log.info(
              "Configured dimensionsSpec is different from the dimensionsSpec of segments. Needs compaction"
          );
          return true;
        }
      }
    }

    if (config.getTransformSpec() != null) {
      final ClientCompactionTaskTransformSpec existingTransformSpec = lastCompactionState.getTransformSpec() != null ?
                                                                      objectMapper.convertValue(lastCompactionState.getTransformSpec(), ClientCompactionTaskTransformSpec.class) :
                                                                      null;
      // Checks for filters
      if (config.getTransformSpec().getFilter() != null) {
        final DimFilter existingFilters = existingTransformSpec != null ?
                                          existingTransformSpec.getFilter() :
                                          null;
        if (!config.getTransformSpec().getFilter().equals(existingFilters)) {
          log.info(
              "Configured filter[%s] is different from the filter[%s] of segments. Needs compaction",
              config.getTransformSpec().getFilter(),
              existingFilters
          );
          return true;
        }
      }
    }

    if (ArrayUtils.isNotEmpty(config.getMetricsSpec())) {
      final AggregatorFactory[] existingMetricsSpec = lastCompactionState.getMetricsSpec() == null || lastCompactionState.getMetricsSpec().isEmpty() ?
                                                      null :
                                                      objectMapper.convertValue(lastCompactionState.getMetricsSpec(), AggregatorFactory[].class);
      if (existingMetricsSpec == null || !Arrays.deepEquals(config.getMetricsSpec(), existingMetricsSpec)) {
        log.info(
            "Configured metricsSpec[%s] is different from the metricsSpec[%s] of segments. Needs compaction",
            Arrays.toString(config.getMetricsSpec()),
            Arrays.toString(existingMetricsSpec)
        );
        return true;
      }
    }

    return false;
  }

  /**
   * Find segments to compact together for the given intervalToSearch. It progressively searches the given
   * intervalToSearch in time order (latest first). The timeline lookup duration is one day. It means, the timeline is
   * looked up for the last one day of the given intervalToSearch, and the next day is searched again if the size of
   * found segments are not enough to compact. This is repeated until enough amount of segments are found.
   *
   * @return segments to compact
   */
  private SegmentsToCompact findSegmentsToCompact(
      final String dataSourceName,
      final CompactibleTimelineObjectHolderCursor compactibleTimelineObjectHolderCursor,
      final DataSourceCompactionConfig config
  )
  {
    final long inputSegmentSize = config.getInputSegmentSizeBytes();

    while (compactibleTimelineObjectHolderCursor.hasNext()) {
      List<DataSegment> segments = compactibleTimelineObjectHolderCursor.next();
      final SegmentsToCompact candidates = new SegmentsToCompact(segments);
      if (!candidates.isEmpty()) {
        final boolean isCompactibleSize = candidates.getTotalSize() <= inputSegmentSize;
        final boolean needsCompaction = needsCompaction(
            config,
            candidates
        );

        if (isCompactibleSize && needsCompaction) {
          if (config.getGranularitySpec() != null && config.getGranularitySpec().getSegmentGranularity() != null) {
            Interval interval = candidates.getUmbrellaInterval();
            Set<Interval> intervalsCompacted = intervalCompactedForDatasource.computeIfAbsent(dataSourceName, k -> new HashSet<>());
            // Skip this candidates if we have compacted the interval already
            if (intervalsCompacted.contains(interval)) {
              continue;
            }
            intervalsCompacted.add(interval);
          }
          return candidates;
        } else {
          if (!needsCompaction) {
            // Collect statistic for segments that is already compacted
            collectSegmentStatistics(compactedSegments, dataSourceName, candidates);
          } else {
            // Collect statistic for segments that is skipped
            // Note that if segments does not need compaction then we do not double count here
            collectSegmentStatistics(skippedSegments, dataSourceName, candidates);
            log.warn(
                "total segment size[%d] for datasource[%s] and interval[%s] is larger than inputSegmentSize[%d]."
                + " Continue to the next interval.",
                candidates.getTotalSize(),
                candidates.segments.get(0).getDataSource(),
                candidates.segments.get(0).getInterval(),
                inputSegmentSize
            );
          }
        }
      } else {
        throw new ISE("No segment is found?");
      }
    }
    log.info("All segments look good! Nothing to compact");
    return new SegmentsToCompact();
  }

  private void collectSegmentStatistics(
      Map<String, CompactionStatistics> statisticsMap,
      String dataSourceName,
      SegmentsToCompact segments)
  {
    CompactionStatistics statistics = statisticsMap.computeIfAbsent(
        dataSourceName,
        v -> CompactionStatistics.initializeCompactionStatistics()
    );
    statistics.incrementCompactedByte(segments.getTotalSize());
    statistics.incrementCompactedIntervals(segments.getNumberOfIntervals());
    statistics.incrementCompactedSegments(segments.getNumberOfSegments());
  }

  /**
   * Returns the initial searchInterval which is {@code (timeline.first().start, timeline.last().end - skipOffset)}.
   *
   * @param timeline      timeline of a dataSource
   * @param skipIntervals intervals to skip
   *
   * @return found interval to search or null if it's not found
   */
  private List<Interval> findInitialSearchInterval(
      String dataSourceName,
      VersionedIntervalTimeline<String, DataSegment> timeline,
      Period skipOffset,
      Granularity configuredSegmentGranularity,
      @Nullable List<Interval> skipIntervals
  )
  {
    Preconditions.checkArgument(timeline != null && !timeline.isEmpty(), "timeline should not be null or empty");
    Preconditions.checkNotNull(skipOffset, "skipOffset");

    final TimelineObjectHolder<String, DataSegment> first = Preconditions.checkNotNull(timeline.first(), "first");
    final TimelineObjectHolder<String, DataSegment> last = Preconditions.checkNotNull(timeline.last(), "last");
    final List<Interval> fullSkipIntervals = sortAndAddSkipIntervalFromLatest(
        last.getInterval().getEnd(),
        skipOffset,
        configuredSegmentGranularity,
        skipIntervals
    );

    // Calcuate stats of all skipped segments
    for (Interval skipInterval : fullSkipIntervals) {
      final List<DataSegment> segments = new ArrayList<>(timeline.findNonOvershadowedObjectsInInterval(skipInterval, Partitions.ONLY_COMPLETE));
      collectSegmentStatistics(skippedSegments, dataSourceName, new SegmentsToCompact(segments));
    }

    final Interval totalInterval = new Interval(first.getInterval().getStart(), last.getInterval().getEnd());
    final List<Interval> filteredInterval = filterSkipIntervals(totalInterval, fullSkipIntervals);
    final List<Interval> searchIntervals = new ArrayList<>();

    for (Interval lookupInterval : filteredInterval) {
      final List<DataSegment> segments = timeline
          .findNonOvershadowedObjectsInInterval(lookupInterval, Partitions.ONLY_COMPLETE)
          .stream()
          // findNonOvershadowedObjectsInInterval() may return segments merely intersecting with lookupInterval, while
          // we are interested only in segments fully lying within lookupInterval here.
          .filter(segment -> lookupInterval.contains(segment.getInterval()))
          .collect(Collectors.toList());

      if (segments.isEmpty()) {
        continue;
      }

      DateTime searchStart = segments
          .stream()
          .map(segment -> segment.getId().getIntervalStart())
          .min(Comparator.naturalOrder())
          .orElseThrow(AssertionError::new);
      DateTime searchEnd = segments
          .stream()
          .map(segment -> segment.getId().getIntervalEnd())
          .max(Comparator.naturalOrder())
          .orElseThrow(AssertionError::new);
      searchIntervals.add(new Interval(searchStart, searchEnd));
    }

    return searchIntervals;
  }

  @VisibleForTesting
  static List<Interval> sortAndAddSkipIntervalFromLatest(
      DateTime latest,
      Period skipOffset,
      Granularity configuredSegmentGranularity,
      @Nullable List<Interval> skipIntervals
  )
  {
    final List<Interval> nonNullSkipIntervals = skipIntervals == null
                                                ? new ArrayList<>(1)
                                                : new ArrayList<>(skipIntervals.size());
    final Interval skipFromLatest;
    if (configuredSegmentGranularity != null) {
      DateTime skipFromLastest = new DateTime(latest, latest.getZone()).minus(skipOffset);
      DateTime skipOffsetBucketToSegmentGranularity = configuredSegmentGranularity.bucketStart(skipFromLastest);
      skipFromLatest = new Interval(skipOffsetBucketToSegmentGranularity, latest);
    } else {
      skipFromLatest = new Interval(skipOffset, latest);
    }

    if (skipIntervals != null) {
      final List<Interval> sortedSkipIntervals = new ArrayList<>(skipIntervals);
      sortedSkipIntervals.sort(Comparators.intervalsByStartThenEnd());

      final List<Interval> overlapIntervals = new ArrayList<>();

      for (Interval interval : sortedSkipIntervals) {
        if (interval.overlaps(skipFromLatest)) {
          overlapIntervals.add(interval);
        } else {
          nonNullSkipIntervals.add(interval);
        }
      }

      if (!overlapIntervals.isEmpty()) {
        overlapIntervals.add(skipFromLatest);
        nonNullSkipIntervals.add(JodaUtils.umbrellaInterval(overlapIntervals));
      } else {
        nonNullSkipIntervals.add(skipFromLatest);
      }
    } else {
      nonNullSkipIntervals.add(skipFromLatest);
    }

    return nonNullSkipIntervals;
  }

  /**
   * Returns a list of intervals which are contained by totalInterval but don't ovarlap with skipIntervals.
   *
   * @param totalInterval total interval
   * @param skipIntervals intervals to skip. This should be sorted by {@link Comparators#intervalsByStartThenEnd()}.
   */
  @VisibleForTesting
  static List<Interval> filterSkipIntervals(Interval totalInterval, List<Interval> skipIntervals)
  {
    final List<Interval> filteredIntervals = new ArrayList<>(skipIntervals.size() + 1);

    DateTime remainingStart = totalInterval.getStart();
    DateTime remainingEnd = totalInterval.getEnd();
    for (Interval skipInterval : skipIntervals) {
      if (skipInterval.getStart().isBefore(remainingStart) && skipInterval.getEnd().isAfter(remainingStart)) {
        remainingStart = skipInterval.getEnd();
      } else if (skipInterval.getStart().isBefore(remainingEnd) && skipInterval.getEnd().isAfter(remainingEnd)) {
        remainingEnd = skipInterval.getStart();
      } else if (!remainingStart.isAfter(skipInterval.getStart()) && !remainingEnd.isBefore(skipInterval.getEnd())) {
        filteredIntervals.add(new Interval(remainingStart, skipInterval.getStart()));
        remainingStart = skipInterval.getEnd();
      } else {
        // Ignore this skipInterval
        log.warn(
            "skipInterval[%s] is not contained in remainingInterval[%s]",
            skipInterval,
            new Interval(remainingStart, remainingEnd)
        );
      }
    }

    if (!remainingStart.equals(remainingEnd)) {
      filteredIntervals.add(new Interval(remainingStart, remainingEnd));
    }

    return filteredIntervals;
  }

  private static class QueueEntry
  {
    private final Interval interval; // whole interval for all segments
    private final List<DataSegment> segments;

    private QueueEntry(List<DataSegment> segments)
    {
      Preconditions.checkArgument(segments != null && !segments.isEmpty());
      DateTime minStart = DateTimes.MAX, maxEnd = DateTimes.MIN;
      for (DataSegment segment : segments) {
        if (segment.getInterval().getStart().compareTo(minStart) < 0) {
          minStart = segment.getInterval().getStart();
        }
        if (segment.getInterval().getEnd().compareTo(maxEnd) > 0) {
          maxEnd = segment.getInterval().getEnd();
        }
      }
      this.interval = new Interval(minStart, maxEnd);
      this.segments = segments;
    }

    private String getDataSource()
    {
      return segments.get(0).getDataSource();
    }
  }

  private static class SegmentsToCompact
  {
    private final List<DataSegment> segments;
    private final long totalSize;

    private SegmentsToCompact()
    {
      this(Collections.emptyList());
    }

    private SegmentsToCompact(List<DataSegment> segments)
    {
      this.segments = segments;
      this.totalSize = segments.stream().mapToLong(DataSegment::getSize).sum();
    }

    private boolean isEmpty()
    {
      return segments.isEmpty();
    }

    private long getTotalSize()
    {
      return totalSize;
    }

    private long getNumberOfSegments()
    {
      return segments.size();
    }

    private Interval getUmbrellaInterval()
    {
      return JodaUtils.umbrellaInterval(segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()));
    }

    private long getNumberOfIntervals()
    {
      return segments.stream().map(DataSegment::getInterval).distinct().count();
    }

    @Override
    public String toString()
    {
      return "SegmentsToCompact{" +
             "segments=" + SegmentUtils.commaSeparatedIdentifiers(segments) +
             ", totalSize=" + totalSize +
             '}';
    }
  }
}
