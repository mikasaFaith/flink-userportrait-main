package com.jhy.task;

import com.jhy.entity.TerminalTypeInfo;
import com.jhy.kafka.KafkaEvent;
import com.jhy.kafka.KafkaEventSchema;
import com.jhy.map.TerminalTypeMap;
import com.jhy.reduce.TerminalTypeReduce;
import com.jhy.reduce.TerminalTypeSink;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;

import javax.annotation.Nullable;

/**
 * 用户终端偏好标签任务类
 *
 * Created by JHy on 2019/05/13
 */
public class TerminalTypeTask {

	public static void main(String[] args) {
		// parse input arguments  目前写死正常是启动任务时通过参数传进来的,目前是和scanProductLog这个topic进行交互
		args = new String[]{"--input-topic","scanProductLog","--bootstrap.servers","192.168.75.20:9092","--zookeeper.connect","192.168.75.20:2181","--group.id","jhy"};
		final ParameterTool parameterTool = ParameterTool.fromArgs(args);

//		if (parameterTool.getNumberOfParameters() < 5) {
//			System.out.println("Missing parameters!\n" +
//					"Usage: Kafka --input-topic <topic> --output-topic <topic> " +
//					"--bootstrap.servers <kafka brokers> " +
//					"--zookeeper.connect <zk quorum> --group.id <some id>");
//			return;
//		}

		/*
		 * 1-- 获取运行时并设置一些环境变量
		 */
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.getConfig().disableSysoutLogging();
		env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(4, 10000));
		env.enableCheckpointing(5000); 								// create a checkpoint every 5 seconds
		env.getConfig().setGlobalJobParameters(parameterTool); 		// make parameters available in the web interface
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		/*
		 * 2-- 添加Source
		 * 		调用自定义水印生成器
		 */
		DataStream<KafkaEvent> input = env
			.addSource(
				new FlinkKafkaConsumer010<>(
					parameterTool.getRequired("input-topic"),
					new KafkaEventSchema(),
					parameterTool.getProperties())
					.assignTimestampsAndWatermarks(new CustomWatermarkExtractor()));

		/*
		 * 3-- 定义算子
		 */
		DataStream<TerminalTypeInfo> terminalTypeMap = input.flatMap(new TerminalTypeMap());
		DataStream<TerminalTypeInfo> terminalTypeReduce = terminalTypeMap.keyBy("groupbyfield").timeWindowAll(Time.seconds(2)).reduce(new TerminalTypeReduce());

		/*
		 * 4-- 定义Sink
		 */
		terminalTypeReduce.addSink(new TerminalTypeSink());

		/*
		 * 5-- 启动程序
		 */
		try {
			env.execute("terminalType analy");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 水印是时间戳，由数据源嵌入或由Flink应用程序生成。
	 *
	 * 周期性水印生成器Periodic Watermark：根据事件或处理时间周期性地触发水印生成器(Assigner)。
	 * 		水印生成器会先调用extractTimestamp方法，然后调用getCurrentWatermark发射水印。
	 * 		这种实现方法是在每个记录后插入水印，相同时间戳的水印不会被发射出去，以确保水印时间戳是严格递增的。
	 */
	private static class CustomWatermarkExtractor implements AssignerWithPeriodicWatermarks<KafkaEvent> {

		private static final long serialVersionUID = -742759155861320823L;

		private long currentTimestamp = Long.MIN_VALUE;

		@Override
		public long extractTimestamp(KafkaEvent event, long previousElementTimestamp) {
			// the inputs are assumed to be of format (message,timestamp)
			this.currentTimestamp = event.getTimestamp();
			return event.getTimestamp();
		}

		@Nullable
		@Override
		public Watermark getCurrentWatermark() {
			return new Watermark(currentTimestamp == Long.MIN_VALUE ? Long.MIN_VALUE : currentTimestamp - 1);
		}
	}

}
