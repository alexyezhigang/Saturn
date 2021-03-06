/**
 * Copyright 2016 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.saturn.job.console.service.impl;

import com.google.common.base.Strings;
import com.vip.saturn.job.console.domain.JobBriefInfo;
import com.vip.saturn.job.console.domain.JobConfig;
import com.vip.saturn.job.console.domain.JobMode;
import com.vip.saturn.job.console.exception.SaturnJobConsoleException;
import com.vip.saturn.job.console.exception.SaturnJobConsoleHttpException;
import com.vip.saturn.job.console.repository.zookeeper.CuratorRepository;
import com.vip.saturn.job.console.service.JobDimensionService;
import com.vip.saturn.job.console.service.JobOperationService;
import com.vip.saturn.job.console.service.RegistryCenterService;
import com.vip.saturn.job.console.service.ServerDimensionService;
import com.vip.saturn.job.console.utils.CronExpression;
import com.vip.saturn.job.console.utils.ExecutorNodePath;
import com.vip.saturn.job.console.utils.JobNodePath;
import com.vip.saturn.job.console.utils.SaturnConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.List;

@Service
public class JobOperationServiceImpl implements JobOperationService {

	private final static Logger logger = LoggerFactory.getLogger(JobOperationServiceImpl.class);

    @Resource
    private RegistryCenterService registryCenterService;
    
    @Resource
    private CuratorRepository curatorRepository;

    @Resource
    private ServerDimensionService serverDimensionService;

    @Resource
	private JobDimensionService jobDimensionService;
    
	@Override
	public void runAtOnceByJobnameAndExecutorName(String jobName, String executorName) {
		runAtOnceByJobnameAndExecutorName(jobName, executorName, curatorRepository.inSessionClient());
	}

	@Override
	public void runAtOnceByJobnameAndExecutorName(String jobName, String executorName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		String path = JobNodePath.getRunOneTimePath(jobName, executorName);
		createNode(curatorFrameworkOp, path);
	}

	private void createNode(CuratorRepository.CuratorFrameworkOp curatorFrameworkOp, String path) {
		if(curatorFrameworkOp.checkExists(path)){
			curatorFrameworkOp.delete(path);
		}
		curatorFrameworkOp.create(path);
	}

	@Override
	public void stopAtOnceByJobnameAndExecutorName(String jobName, String executorName) {
		stopAtOnceByJobnameAndExecutorName(jobName, executorName, curatorRepository.inSessionClient());
	}

	@Override
	public void stopAtOnceByJobnameAndExecutorName(String jobName, String executorName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		String path = JobNodePath.getStopOneTimePath(jobName, executorName);
		createNode(curatorFrameworkOp, path);
	}

	@Override
	public void setJobEnabledState(String jobName, boolean state) {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = curatorRepository.inSessionClient();
		curatorFrameworkOp.update(JobNodePath.getConfigNodePath(jobName, "enabled"), state);
	}

	@Override
	public void validateJobConfig(JobConfig jobConfig) throws SaturnJobConsoleException {
		// 作业名必填
		if (jobConfig.getJobName() == null || jobConfig.getJobName().trim().isEmpty()) {
			throw new SaturnJobConsoleException("作业名必填");
		}
		// 作业名只允许包含：数字0-9、小写字符a-z、大写字符A-Z、下划线_
		if (!jobConfig.getJobName().matches("[0-9a-zA-Z_]*")) {
			throw new SaturnJobConsoleException("作业名只允许包含：数字0-9、小写字符a-z、大写字符A-Z、下划线_");
		}
		// 依赖的作业只允许包含：数字0-9、小写字符a-z、大写字符A-Z、下划线_、英文逗号,
		if (jobConfig.getDependencies() != null && !jobConfig.getDependencies().matches("[0-9a-zA-Z_,]*")) {
			throw new SaturnJobConsoleException("依赖的作业只允许包含：数字0-9、小写字符a-z、大写字符A-Z、下划线_、英文逗号,");
		}
		// 作业类型必填
		if (jobConfig.getJobType() == null || jobConfig.getJobType().trim().isEmpty()) {
			throw new SaturnJobConsoleException("作业类型必填");
		}
		// 验证作业类型
		if (JobBriefInfo.JobType.getJobType(jobConfig.getJobType()).equals(JobBriefInfo.JobType.UNKOWN_JOB)) {
			throw new SaturnJobConsoleException("作业类型未知");
		}
		// 验证VSHELL类型版本兼容性
		if (JobBriefInfo.JobType.getJobType(jobConfig.getJobType()).equals(JobBriefInfo.JobType.VSHELL) && jobDimensionService.isNewSaturn("1.1.2") != 2) {
			throw new SaturnJobConsoleException("Shell消息作业导入要求所有executor版本都是1.1.2及以上");
		}
		// 如果是JAVA/MSG作业
		if (jobConfig.getJobType().equals(JobBriefInfo.JobType.JAVA_JOB.name()) || jobConfig.getJobType().equals(JobBriefInfo.JobType.MSG_JOB.name())) {
			// 作业实现类必填
			if (jobConfig.getJobClass() == null || jobConfig.getJobClass().trim().isEmpty()) {
				throw new SaturnJobConsoleException("对于JAVA/MSG作业，作业实现类必填");
			}
		}
		// 如果是JAVA/SHELL作业
		if (jobConfig.getJobType().equals(JobBriefInfo.JobType.JAVA_JOB.name()) || jobConfig.getJobType().equals(JobBriefInfo.JobType.SHELL_JOB.name())) {
			// cron表达式必填
			if (jobConfig.getCron() == null || jobConfig.getCron().trim().isEmpty()) {
				throw new SaturnJobConsoleException("对于JAVA/SHELL作业，cron表达式必填");
			}
			// cron表达式语法验证
			try {
				CronExpression.validateExpression(jobConfig.getCron());
			} catch (ParseException e) {
				throw new SaturnJobConsoleException("cron表达式语法有误，" + e.toString());
			}
		} else {
			jobConfig.setCron("");
			;// 其他类型的不需要持久化保存cron表达式
		}
		if (jobConfig.getLocalMode() != null && jobConfig.getLocalMode()) {
			if (jobConfig.getShardingItemParameters() == null) {
				throw new SaturnJobConsoleException("对于本地模式作业，分片参数必填。");
			} else {
				String[] split = jobConfig.getShardingItemParameters().split(",");
				boolean includeXing = false;
				for (String tmp : split) {
					String[] split2 = tmp.split("=");
					if ("*".equalsIgnoreCase(split2[0].trim())) {
						includeXing = true;
						break;
					}
				}
				if (!includeXing) {
					throw new SaturnJobConsoleException("对于本地模式作业，分片参数必须包含如*=xx。");
				}
			}
		} else {
			// 分片参数不能小于分片总数
			if (jobConfig.getShardingTotalCount() == null || jobConfig.getShardingTotalCount() < 1) {
				throw new SaturnJobConsoleException("分片数不能为空，并且不能小于1");
			}
			if (jobConfig.getShardingTotalCount() > 0) {
				if (jobConfig.getShardingItemParameters() == null || jobConfig.getShardingItemParameters().trim().isEmpty() || jobConfig.getShardingItemParameters().split(",").length < jobConfig.getShardingTotalCount()) {
					throw new SaturnJobConsoleException("分片参数不能小于分片总数");
				}
			}
		}
		// 不能添加系统作业
		if (jobConfig.getJobMode() != null && jobConfig.getJobMode().startsWith(JobMode.SYSTEM_PREFIX)) {
			throw new SaturnJobConsoleException("作业模式有误，不能添加系统作业");
		}
	}

	@Override
	public void persistJob(JobConfig jobConfig, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		if(curatorFrameworkOp == null){
			curatorFrameworkOp = curatorRepository.inSessionClient();
		}

		jobConfig.setDefaultValues();
		String jobName = jobConfig.getJobName();

		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "enabled"), "false");
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "description"), jobConfig.getDescription());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobType"), jobConfig.getJobType());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobMode"), jobConfig.getJobMode());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "shardingItemParameters"), jobConfig.getShardingItemParameters());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobParameter"), jobConfig.getJobParameter());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "queueName"), jobConfig.getQueueName());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "channelName"), jobConfig.getChannelName());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "failover"), "true");
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "timeout4AlarmSeconds"), jobConfig.getTimeout4AlarmSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "timeoutSeconds"), jobConfig.getTimeoutSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "timeZone"), jobConfig.getTimeZone());
		if(JobBriefInfo.JobType.MSG_JOB.name().equals(jobConfig.getJobType())){// MSG作业没有cron表达式
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "cron"), "");
		}else{
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "cron"), jobConfig.getCron());
		}
		if(!Strings.isNullOrEmpty(jobConfig.getPausePeriodDate())){
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "pausePeriodDate"), jobConfig.getPausePeriodDate());
		}
		if(!Strings.isNullOrEmpty(jobConfig.getPausePeriodTime())){
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "pausePeriodTime"), jobConfig.getPausePeriodTime());
		}
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "processCountIntervalSeconds"), jobConfig.getProcessCountIntervalSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "shardingTotalCount"), jobConfig.getShardingTotalCount());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "showNormalLog"), jobConfig.getShowNormalLog());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "loadLevel"), jobConfig.getLoadLevel());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobDegree"), jobConfig.getJobDegree());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "enabledReport"), jobConfig.getEnabledReport());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "preferList"), jobConfig.getPreferList());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "useDispreferList"), jobConfig.getUseDispreferList());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "localMode"), jobConfig.getLocalMode());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "useSerial"), jobConfig.getUseSerial());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "dependencies"), jobConfig.getDependencies());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "groups"), jobConfig.getGroups());
		if(JobBriefInfo.JobType.SHELL_JOB.name().equals(jobConfig.getJobType())){
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobClass"), "");
		}else{
			curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, "jobClass"), jobConfig.getJobClass());
		}
	}

	@Override
	public void deleteJob(String jobName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) throws SaturnJobConsoleException {
		try {
			String enabledNodePath = JobNodePath.getConfigNodePath(jobName, "enabled");
			long creationTime = curatorFrameworkOp.getCtime(enabledNodePath);
			long timeDiff = System.currentTimeMillis() - creationTime;
			if (timeDiff < SaturnConstants.JOB_CAN_BE_DELETE_TIME_LIMIT) {
				String errMessage = "Job cannot be deleted until " + (SaturnConstants.JOB_CAN_BE_DELETE_TIME_LIMIT / 1000) + " seconds after job creation";
				throw new SaturnJobConsoleHttpException(HttpStatus.BAD_REQUEST.value(), errMessage);
			}

			//1.作业的executor全online的情况，添加toDelete节点，触发监听器动态删除节点
			String toDeleteNodePath = JobNodePath.getConfigNodePath(jobName, "toDelete");
			if (curatorFrameworkOp.checkExists(toDeleteNodePath)) {
				curatorFrameworkOp.deleteRecursive(toDeleteNodePath);
			}
			curatorFrameworkOp.create(toDeleteNodePath);

			for (int i = 0; i < 20; i++) {
				// 2.作业的executor全offline的情况，或有几个online，几个offline的情况
				String jobServerPath = JobNodePath.getServerNodePath(jobName);
				if (!curatorFrameworkOp.checkExists(jobServerPath)) {
					// (1)如果不存在$Job/JobName/servers节点，说明该作业没有任何executor接管，可直接删除作业节点
					curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
					return;
				}
				// (2)如果该作业servers下没有任何executor，可直接删除作业节点
				List<String> executors = curatorFrameworkOp.getChildren(jobServerPath);
				if (CollectionUtils.isEmpty(executors)) {
					curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
					return;
				}
				// (3)只要该作业没有一个能运行的该作业的executor在线，那么直接删除作业节点
				boolean hasOnlineExecutor = false;
				for (String executor : executors) {
					if (curatorFrameworkOp.checkExists(ExecutorNodePath.getExecutorNodePath(executor, "ip"))
							&& curatorFrameworkOp.checkExists(JobNodePath.getServerStatus(jobName, executor))) {
						hasOnlineExecutor = true;
						break;
					}
				}
				if (!hasOnlineExecutor) {
					curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
				}

				Thread.sleep(200);
			}
		} catch (SaturnJobConsoleException e) {
			throw e;
		} catch (Throwable t) {
			logger.error("exception is thrown during delete job", t);
			throw new SaturnJobConsoleHttpException(HttpStatus.INTERNAL_SERVER_ERROR.value(), t.getMessage(), t);
		}
	}

	@Override
	public void copyAndPersistJob(JobConfig jobConfig, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) throws Exception {
		if(curatorFrameworkOp == null){
			curatorFrameworkOp = curatorRepository.inSessionClient();
		}

		String originJobName = jobConfig.getOriginJobName();
		List<String> jobConfigNodes = curatorFrameworkOp.getChildren(JobNodePath.getConfigNodePath(originJobName));
		if(CollectionUtils.isEmpty(jobConfigNodes)){
			return;
		}
		Class<?> cls = jobConfig.getClass();
		String jobClassPath = "";
		String jobClassValue = "";
		for(String jobConfigNode : jobConfigNodes){
			String jobConfigPath = JobNodePath.getConfigNodePath(originJobName, jobConfigNode);
			String jobConfigValue = curatorFrameworkOp.getData(jobConfigPath);
			if("enabled".equals(jobConfigNode)){// enabled固定为false
				String fillJobNodePath = JobNodePath.getConfigNodePath(jobConfig.getJobName(), jobConfigNode);
				curatorFrameworkOp.fillJobNodeIfNotExist(fillJobNodePath,"false");
				continue;
			}
			if("failover".equals(jobConfigNode)){// failover固定为true
				String fillJobNodePath = JobNodePath.getConfigNodePath(jobConfig.getJobName(), jobConfigNode);
				curatorFrameworkOp.fillJobNodeIfNotExist(fillJobNodePath,"true");
				continue;
			}
			try{
				Field field = cls.getDeclaredField(jobConfigNode);
				field.setAccessible(true);
				Object fieldValue = field.get(jobConfig);
				if(fieldValue != null){
					jobConfigValue = fieldValue.toString();
				}
				if("jobClass".equals(jobConfigNode)){// 持久化jobClass会触发添加作业，待其他节点全部持久化完毕以后再持久化jobClass
					jobClassPath = JobNodePath.getConfigNodePath(jobConfig.getJobName(), jobConfigNode);
					jobClassValue = jobConfigValue;
				}
			}catch(NoSuchFieldException e){// 即使JobConfig类中不存在该属性也复制（一般是旧版作业的一些节点，可以在旧版Executor上运行）
				continue;
			}finally{
				if(!"jobClass".equals(jobConfigNode)){// 持久化jobClass会触发添加作业，待其他节点全部持久化完毕以后再持久化jobClass
					String fillJobNodePath = JobNodePath.getConfigNodePath(jobConfig.getJobName(), jobConfigNode);
					curatorFrameworkOp.fillJobNodeIfNotExist(fillJobNodePath,jobConfigValue);
				}
			}
		}
		if(!Strings.isNullOrEmpty(jobClassPath)){
			curatorFrameworkOp.fillJobNodeIfNotExist(jobClassPath,jobClassValue);
		}
	}
}
