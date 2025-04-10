package com.sxc.ojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;
import com.sxc.ojcodesandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    private static final Boolean FIRST_INIT=true;

    private static final long TIME_OUT = 5000L;

    public static void main(String[] args) {
        //拿testCode.simpleComputeArgs/Main.java测试
        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2","3 4"));
        executeCodeRequest.setLanguage("java");
        //ResourceUtil.readStr:从resource目录下读文件
        String code= ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        ExecuteCodeResponse executeCodeResponse= javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }



    /**
     * 3、创建容器，把文件复制到容器内
     * @param userCodeFile
     * @param inputList
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        //获取默认的Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        //3.拉取镜像
        String image = "openjdk:8-alpine";
        //镜像只需要拉取一次
        if (FIRST_INIT) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }

        //4.创建容器
        CreateContainerCmd createContainerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeFile.getParentFile().getAbsolutePath(), new Volume("/app")));  //容器挂载目录
        String profileConfig = ResourceUtil.readUtf8Str("profile.json");   //在 hostConfig 中开启安全机制：
        hostConfig.withSecurityOpts(Arrays.asList("seccomp=" + profileConfig));

        CreateContainerResponse createContainerResponse = createContainerCmd
                .withHostConfig(hostConfig)            //创建容器时，设置网络配置为关闭
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)        //限制用户不能向 root 根目录写文件
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)         //交互
                .exec();
        System.out.println(createContainerResponse);
        String containerId = createContainerResponse.getId();

        //5.启动容器
        dockerClient.startContainerCmd(containerId).exec();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();

        //docker exec keen_blackwell java -cp /app Main 1 3
        //遍历执行输入用例
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();
            //创建运行class文件命令
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();

            System.out.println("创建的执行命令：" + execCreateCmdResponse);

            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            long time = 0L;


            //执行命令并获取结果
            // 判断是否超时
            final boolean[] timeout = {true};
            String execId = execCreateCmdResponse.getId();
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {

                @Override
                public void onComplete() {
                    // 如果执行完成，则表示没超时
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果：" + new String(frame.getPayload()));
                    } else {
                        message[0] = new String(frame.getPayload());
                        System.out.println("输出结果：" + new String(frame.getPayload()));
                    }
                    super.onNext(frame);
                }
            };

            final long[] maxMemory = {0L};

            // 获取占用的内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {

                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.exec(statisticsResultCallback);


            //执行命令
            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);
                stopWatch.stop();
                time = stopWatch.getLastTaskTimeMillis();
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setMessage(message[0]);
            System.out.println("time：" + time);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);



        }
        return executeMessageList;

    }

}
