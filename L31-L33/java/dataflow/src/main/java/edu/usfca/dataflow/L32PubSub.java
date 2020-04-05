package edu.usfca.dataflow;

import java.util.stream.StreamSupport;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Trigger;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

public class L32PubSub {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  // TODO: Make sure you change the following parameters according to your own settings.
  final static String GCP_PROJECT_ID = "beer-spear";
  final static String TOPIC_ID = "cs686-test";
  final static String GCS_BUCKET = "gs://usf-cs686-sp20";
  final static String OUTPUT_DIR = String.format("%s/L32-output/files", GCS_BUCKET);
  final static String REGION = "us-west1";

  static DataflowPipelineOptions getOptions() {
    String jobName = String.format("test-job-%06d", Instant.now().getMillis() % 1000000);

    DataflowPipelineOptions options = PipelineOptionsFactory.create().as(DataflowPipelineOptions.class);

    // This will display the "current settings" stored in options.
    System.out.println(options.toString());

    options.setTempLocation(GCS_BUCKET + "/staging");
    options.setJobName(jobName);
    // options.setRunner(DataflowRunner.class); // <- Runner on GCP
    // NOTE: Use local runner first, as it's easier to check the results on your end.
    options.setRunner(DirectRunner.class); // <- Local Runner
    options.setMaxNumWorkers(1);
    options.setWorkerMachineType("n1-standard-1");
    options.setDiskSizeGb(150);
    options.setRegion(REGION);
    options.setProject(GCP_PROJECT_ID);

    // You will see more info here.
    // To run a pipeline (job) on GCP via Dataflow, you need to specify a few things like the ones above.
    System.out.println(options.toString());
    return options;
  }

  public static void execute(String[] args, int job) {
    DataflowPipelineOptions options = getOptions();

    Pipeline p = Pipeline.create(options);

    PCollection<String> unboundedPc = p.apply("Read from Pubsub",
        PubsubIO.readStrings().fromTopic(String.format("projects/%s/topics/%s", GCP_PROJECT_ID, TOPIC_ID)));

    // TODO: Follow the instructions about publishing messages to PubSub (see L32 slides).

    // Try one of the following (separately).

    switch (job) {
      case 1:
        // Pipeline #1.
        FixedAndGbkWithOutput(unboundedPc).run();
        break;

      case 2:
        // Pipeline #2.
        GlobalWindowAndGbkWithAccumulatingPanes(unboundedPc).run();
        break;

      case 3:
        // Pipeline #3.
        GlobalWindowAndGbkWithDiscardingPanes(unboundedPc).run();
      default:
        break;
    }

    System.out.println("ta-da!");
  }

  private static Pipeline FixedAndGbkWithOutput(PCollection<String> unboundedPc) {
    // Pipeline branch #1: Apply fixed windows, and do GBK.
    unboundedPc.apply("Fixed Windows", Window.<String>into(FixedWindows.of(Duration.standardSeconds(10))))//
        .apply("To KV", ParDo.of(new DoFn<String, KV<String, Long>>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            c.output(KV.of(c.element(), c.timestamp().getMillis()));
            LOG.info("[MyAck] Got message {} at {} {} | pane: {} | window: {}", c.element(), c.timestamp().getMillis(),
                c.timestamp().toString(), c.pane().toString(), w.maxTimestamp());
          }
        })) // NOTE: Using DirectRunner (local runner), "GBK" and the subsequent code may never get executed.
        // It's a known issue in Beam SDK. Yet, it'll work fine if you use DataflowRunner (on GCP).
        .apply("GBK", GroupByKey.create()).apply("Leave Logs", ParDo.of(new DoFn<KV<String, Iterable<Long>>, String>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            final int numValues = Iterables.size(c.element().getValue());
            final long maxValue = StreamSupport.stream(c.element().getValue().spliterator(), false).max(Long::compareTo)
                .orElse(Long.MIN_VALUE);
            c.output(String.format("message %s with %d values", c.element().getKey(), numValues));
            LOG.info("[Post-GBK] Message {} with {} values whose max is {} | pane: {} | window : {}",
                c.element().getKey(), numValues, maxValue, c.pane().toString(), w.maxTimestamp());
          }
        })).apply("To GCS", new WriteOneFilePerWindow(OUTPUT_DIR, 1));
    // NOTE: This write step will only work when you run your job on GCP.

    return unboundedPc.getPipeline();
  }

  /**
   * https://console.cloud.google.com/dataflow/jobsDetail/locations/us-west1/jobs/2020-03-24_17_06_38-3738382172513352688?project=beer-spear
   *
   * 2020-03-24 (17:08:47) [Post-GBK] Message abc with 1 values whose max is 1585094899589 | pane:
   * PaneInfo{isFirst=true, timin...
   *
   * 2020-03-24 (17:08:47) [Post-GBK] Message abc with 4 values whose max is 1585094899589 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:08:47) [Post-GBK] Message abc with 8 values whose max is 1585094901124 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:09:06) [Post-GBK] Message abc with 10 values whose max is 1585094946392 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   * 2020-03-24 (17:09:09) [Post-GBK] Message abc with 11 values whose max is 1585094947863 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   * 2020-03-24 (17:09:20) [Post-GBK] Message abc with 12 values whose max is 1585094958864 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   * 2020-03-24 (17:09:21) [Post-GBK] Message abc with 13 values whose max is 1585094960305 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   * 2020-03-24 (17:09:26) [Post-GBK] Message abc with 14 values whose max is 1585094964899 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   * 2020-03-24 (17:09:27) [Post-GBK] Message abc with 15 values whose max is 1585094966097 | pane:
   * PaneInfo{timing=EARLY, inde...
   *
   */
  public static Pipeline GlobalWindowAndGbkWithAccumulatingPanes(PCollection<String> unboundedPc) {
    // This trigger will continuously fire panes as long as input elements are coming in.
    Trigger trigger = Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane());

    // With "accumulatingFiredPanes", you would see that all elements will be retained (which can be confirmed via
    // "[Post-GBK]" logs.
    unboundedPc.apply("Global Windows", Window.<String>configure().triggering(trigger).accumulatingFiredPanes())
        .apply("To Kv", ParDo.of(new DoFn<String, KV<String, Long>>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            c.output(KV.of(c.element(), c.timestamp().getMillis()));
            LOG.info("[MyAck] Got message {} at {} {} | pane: {} | window: {}", c.element(), c.timestamp().getMillis(),
                c.timestamp().toString(), c.pane().toString(), w.maxTimestamp());
          }
        })) // NOTE: Using DirectRunner (local runner), "GBK" and the subsequent code may never get executed.
        .apply("GBK", GroupByKey.create()).apply("Leave Logs", ParDo.of(new DoFn<KV<String, Iterable<Long>>, String>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            final int numValues = Iterables.size(c.element().getValue());
            final long maxValue = StreamSupport.stream(c.element().getValue().spliterator(), false).max(Long::compareTo)
                .orElse(Long.MIN_VALUE);
            c.output(String.format("message %s with %d values", c.element().getKey(), numValues));
            LOG.info("[Post-GBK] Message {} with {} values whose max is {} | pane: {} | window : {}",
                c.element().getKey(), numValues, maxValue, c.pane().toString(), w.maxTimestamp());
          }
        }));

    return unboundedPc.getPipeline();
  }

  /**
   * https://console.cloud.google.com/dataflow/jobsDetail/locations/us-west1/jobs/2020-03-24_17_11_48-11200315980263075918?project=beer-spear
   *
   * 2020-03-24 (17:13:47) [Post-GBK] Message abc with 5 values whose max is 1585095178452 | pane:
   * PaneInfo{isFirst=true, timin...
   *
   * 2020-03-24 (17:13:47) [Post-GBK] Message abc with 6 values whose max is 1585095226319 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:48) [Post-GBK] Message abc with 1 values whose max is 1585095227759 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:51) [Post-GBK] Message abc with 1 values whose max is 1585095229425 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:52) [Post-GBK] Message abc with 1 values whose max is 1585095231149 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:53) [Post-GBK] Message abc with 1 values whose max is 1585095232460 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:55) [Post-GBK] Message abc with 1 values whose max is 1585095234111 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:13:59) [Post-GBK] Message abc with 1 values whose max is 1585095238714 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:14:01) [Post-GBK] Message abc with 1 values whose max is 1585095240333 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:14:03) [Post-GBK] Message abc with 1 values whose max is 1585095241960 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   * 2020-03-24 (17:14:16) [Post-GBK] Message abc with 1 values whose max is 1585095256080 | pane:
   * PaneInfo{timing=EARLY, index...
   *
   */
  public static Pipeline GlobalWindowAndGbkWithDiscardingPanes(PCollection<String> unboundedPc) {
    // This trigger will continuously fire panes as long as input elements are coming in.
    // Trigger trigger = Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane());
    Trigger trigger = Repeatedly.forever(AfterPane.elementCountAtLeast(2));

    // With "discardingFiredPanes", you would see that elements will be discarded after they are grouped(which can be
    // confirmed via
    // "[Post-GBK]" logs.
    unboundedPc.apply("Global Windows", Window.<String>configure().triggering(trigger).discardingFiredPanes())
        .apply("To Kv", ParDo.of(new DoFn<String, KV<String, Long>>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            c.output(KV.of(c.element(), c.timestamp().getMillis()));
            LOG.info("[MyAck] Got message {} at {} {} | pane: {} | window: {}", c.element(), c.timestamp().getMillis(),
                c.timestamp().toString(), c.pane().toString(), w.maxTimestamp());
          }
        })) // NOTE: Using DirectRunner (local runner), "GBK" and the subsequent code may never get executed.
        .apply("GBK", GroupByKey.create()).apply("Leave Logs", ParDo.of(new DoFn<KV<String, Iterable<Long>>, String>() {
          @ProcessElement
          public void process(ProcessContext c, BoundedWindow w) {
            final int numValues = Iterables.size(c.element().getValue());
            final long maxValue = StreamSupport.stream(c.element().getValue().spliterator(), false).max(Long::compareTo)
                .orElse(Long.MIN_VALUE);
            c.output(String.format("message %s with %d values", c.element().getKey(), numValues));
            LOG.info("[Post-GBK] Message {} with {} values whose max is {} | pane: {} | window : {}",
                c.element().getKey(), numValues, maxValue, c.pane().toString(), w.maxTimestamp());
          }
        }));

    return unboundedPc.getPipeline();
  }
}

