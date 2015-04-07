/*
 * Cloud9: A MapReduce Library for Hadoop
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package edu.umd.cloud9.examples;

import edu.umd.cloud9.io.PairOfStrings;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Iterator;
import java.util.StringTokenizer;

/**
 * <p>
 * Simple previousWord count demo. This Hadoop Tool counts words in flat text file, and
 * takes the following command-line arguments:
 * </p>
 * 
 * <ul>
 * <li>[input-path] input path</li>
 * <li>[output-path] output path</li>
 * <li>[num-mappers] number of mappers</li>
 * <li>[num-reducers] number of reducers</li>
 * </ul>
 * 
 * @author Jimmy Lin
 * @author Marc Sloan
 */
public class BigramRelativeFrequency extends Configured implements Tool {
	private static final Logger sLogger = Logger.getLogger(BigramRelativeFrequency.class);

	/**
	 *  Mapper: emits (token, 1) for every previousWord occurrence
	 *
	 */
	private static class MyMapper extends MapReduceBase implements
	Mapper<LongWritable, Text, PairOfStrings, FloatWritable> {

		/**
		 *  Store an IntWritable with a value of 1, which will be mapped
		 *  to each previousWord found in the test
		 */
		private final static FloatWritable one = new FloatWritable(1.0f);
		/**
		 * reuse objects to save overhead of object creation
		 */
		private PairOfStrings bigram = new PairOfStrings();


		/**
		 * Mapping function. This takes the text input, converts it into a String which is split into
		 * words, then each of the words is mapped to the OutputCollector with a count of
		 * one.
		 *
		 * @param key Input key, not used in this example
		 * @param value A line of input Text taken from the data
		 * @param output Map from each previousWord (Text) to its count (IntWritable)
		 */
		public void map(LongWritable key, Text value, OutputCollector<PairOfStrings, FloatWritable> output,
				Reporter reporter) throws IOException {
			//Convert input previousWord into String and tokenize to find words
			String line = ((Text) value).toString();
			StringTokenizer itr = new StringTokenizer(line);
			//For each previousWord, map it to a count of one. Duplicate words will be counted
			//in the reduce phase.
			String prev = null;

			while (itr.hasMoreTokens()) {
				String cur = itr.nextToken();

				//Emit only if we have an actual bigram
				if (prev != null) {
					if (cur.length() > 100) {
						cur = cur.substring(0, 100);
					}

					if (prev.length() > 100) {
						prev = prev.substring(0, 100);
					}
					bigram.set(prev, cur);
					output.collect(bigram, one);
					
					bigram.set(prev, "*");
					output.collect(bigram, one);
				}

				prev = cur;

			}
		}
	}

	private static class MyCombiner extends MapReduceBase implements
	Reducer<PairOfStrings, FloatWritable, PairOfStrings, FloatWritable> {

		private static final FloatWritable SUM = new FloatWritable();

		@Override
		public void reduce(PairOfStrings key, Iterator<FloatWritable> values,
				OutputCollector<PairOfStrings, FloatWritable> output, Reporter reporter) throws IOException {

			int sum = 0;
			while (values.hasNext()) {
				sum += values.next().get();
			}
			SUM.set(sum);
			output.collect(key, SUM);
		}

	}

	/**
	 * Reducer: sums up all the counts
	 *
	 */
	private static class MyReducer extends MapReduceBase implements
	Reducer<PairOfStrings, FloatWritable, PairOfStrings, FloatWritable> {

		/**
		 *  Stores the sum of counts for a previousWord
		 */
		private final static FloatWritable Sum = new FloatWritable();
		private float marginal = 0.0f;

		/**
		 *  @param key The Text previousWord
		 *  @param values An iterator over the values associated with this previousWord
		 *  @param output Map from each previousWord (Text) to its count (IntWritable)
		 *  @param reporter Used to report progress
		 */
		public void reduce(PairOfStrings key, Iterator<FloatWritable> values,
				OutputCollector<PairOfStrings, FloatWritable> output, Reporter reporter) throws IOException {
			// sum up values
			float sum = 0.0f;
			while (values.hasNext()) {
				sum += values.next().get();
			}

			if (key.getRightElement().equals("*")) {
				Sum.set(sum);
				output.collect(key, Sum);
				marginal = sum;
			} else {
				Sum.set(sum / marginal);
				output.collect(key, Sum);
			}
		}
	}

	protected static class MyPartitioner extends MapReduceBase implements
	Partitioner<PairOfStrings, FloatWritable> {
		@Override
		public int getPartition(PairOfStrings key, FloatWritable value, int numReduceTasks) {
			return (key.getLeftElement().hashCode() & Integer.MAX_VALUE) % numReduceTasks;
		}
	}

	/**
	 * Creates an instance of this tool.
	 */
	public BigramRelativeFrequency() {
	}

	/**
	 *  Prints argument options
	 * @return
	 */
	private static int printUsage() {
		System.out.println("usage: [input-path] [output-path] [num-mappers] [num-reducers]");
		ToolRunner.printGenericCommandUsage(System.out);
		return -1;
	}

	/**
	 * Runs this tool.
	 */
	public int run(String[] args) throws Exception {
		if (args.length != 4) {
			printUsage();
			return -1;
		}

		String inputPath = args[0];
		String outputPath = args[1];

		int mapTasks = Integer.parseInt(args[2]);
		int reduceTasks = Integer.parseInt(args[3]);

		sLogger.info("Tool: DemoWordCount");
		sLogger.info(" - input path: " + inputPath);
		sLogger.info(" - output path: " + outputPath);
		sLogger.info(" - number of mappers: " + mapTasks);
		sLogger.info(" - number of reducers: " + reduceTasks);

		JobConf conf = new JobConf(BigramRelativeFrequency.class);
		conf.setJobName("DemoWordCount");

		conf.setNumMapTasks(mapTasks);
		conf.setNumReduceTasks(reduceTasks);

		FileInputFormat.setInputPaths(conf, new Path(inputPath));
		FileOutputFormat.setOutputPath(conf, new Path(outputPath));
		FileOutputFormat.setCompressOutput(conf, false);

		/**
		 *  Note that these must match the Class arguments given in the mapper 
		 */
		conf.setOutputKeyClass(PairOfStrings.class);
		conf.setOutputValueClass(FloatWritable.class);

		conf.setMapperClass(MyMapper.class);
		conf.setCombinerClass(MyCombiner.class);
		conf.setReducerClass(MyReducer.class);
		conf.setPartitionerClass(MyPartitioner.class);

		// Delete the output directory if it exists already
		Path outputDir = new Path(outputPath);
		FileSystem.get(outputDir.toUri(), conf).delete(outputDir, true);

		long startTime = System.currentTimeMillis();
		JobClient.runJob(conf);
		sLogger.info("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0
				+ " seconds");

		return 0;
	}

	/**
	 * Dispatches command-line arguments to the tool via the
	 * <code>ToolRunner</code>.
	 */
	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new BigramRelativeFrequency(), args);
		System.exit(res);
	}
}