package ideal.sylph.main.service;

import com.google.inject.Inject;
import ideal.sylph.spi.Runner;
import ideal.sylph.spi.RunnerContext;
import ideal.sylph.spi.classloader.ThreadContextClassLoader;
import ideal.sylph.spi.exception.SylphException;
import ideal.sylph.spi.job.Flow;
import ideal.sylph.spi.job.Job;
import ideal.sylph.spi.job.JobActuator;
import ideal.sylph.spi.job.JobContainer;
import ideal.sylph.spi.job.JobHandle;
import ideal.sylph.spi.job.YamlFlow;

import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static ideal.sylph.spi.exception.StandardErrorCode.JOB_BUILD_ERROR;
import static java.util.Objects.requireNonNull;

/**
 * RunnerManger
 */
public class RunnerManger
{
    private final Map<String, JobActuator> jobActuatorMap = new HashMap<>();
    private final RunnerContext runnerContext;

    @Inject
    public RunnerManger(
            RunnerContext runnerContext
    )
    {
        this.runnerContext = requireNonNull(runnerContext, "runnerContext is null");
    }

    public void createRunner(Runner runner)
    {
        runner.create(runnerContext).forEach(jobActuator -> {
            JobActuatorProxy dynamicProxy = new JobActuatorProxy(jobActuator);
            JobActuator proxy = (JobActuator) dynamicProxy.getProxy(JobActuator.class);

            for (String name : proxy.getInfo().getName()) {
                if (jobActuatorMap.containsKey(name)) {
                    throw new IllegalArgumentException(String.format("Multiple entries with same key: %s=%s and %s=%s", name, jobActuatorMap.get(name), name, jobActuator));
                }
                else {
                    jobActuatorMap.put(name, jobActuator);
                }
            }
        });
    }

    /**
     * 创建job 运行时
     */
    public JobContainer createJobContainer(@Nonnull Job job, Optional<String> jobInfo)
    {
        String jobType = requireNonNull(job.getActuatorName(), "job Actuator Name is null " + job.getId());
        JobActuator jobActuator = jobActuatorMap.get(jobType);
        checkArgument(jobActuator != null, jobType + " not exists");
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(jobActuator.getClass().getClassLoader())) {
            return jobActuator.createJobContainer(job, jobInfo);
        }
    }

    public Job formJobWithDir(File jobDir, Map<String, String> jobProps)
    {
        String jobType = requireNonNull(jobProps.get("type"), "jobProps arg type is null");
        try {
            Flow flow = YamlFlow.load(new File(jobDir, "job.yaml"));
            //----create jobClassLoader
            //DirClassLoader jobClassLoader = new DirClassLoader(null,);
            //jobClassLoader.addDir(jobDir);
            return formJobWithFlow(jobDir.getName(), flow, jobType);
        }
        catch (IOException e) {
            throw new SylphException(JOB_BUILD_ERROR, "loadding job " + jobDir + " job.yaml fail", e);
        }
    }

    public Job formJobWithFlow(String jobId, Flow flow, String actuatorName)
    {
        requireNonNull(actuatorName, "job actuatorName is null");
        JobActuator jobActuator = jobActuatorMap.get(actuatorName);
        checkArgument(jobActuator != null, "job [" + jobId + "] loading error! JobActuator:[" + actuatorName + "] not exists,only " + jobActuatorMap.keySet());
        //--- 通过代理 返回job类型信息 ---
        //jobActuator.getClass().getClassLoader();

        JobHandle jobHandle = jobActuator.formJob(jobId, flow);
        return new Job()
        {
            @NotNull
            @Override
            public String getId()
            {
                return jobId;
            }

            @NotNull
            @Override
            public String getActuatorName()
            {
                return actuatorName;
            }

            @NotNull
            @Override
            public JobHandle getJobHandle()
            {
                return jobHandle;
            }

            @NotNull
            @Override
            public Flow getFlow()
            {
                return flow;
            }
        };
    }

    public Collection<JobActuator.ActuatorInfo> getAllActuatorsInfo()
    {
        return jobActuatorMap.values()
                .stream()
                .distinct().map(JobActuator::getInfo)
                .collect(Collectors.toList());
    }
}
