package com.sxc.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;
import com.sxc.ojcodesandbox.model.ExecuteMessage;
import com.sxc.ojcodesandbox.model.JudgeInfo;
import com.sxc.ojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public class JavaDockerCodeSandboxOld implements CodeSandbox {


    private static final String GLOBAL_JAVA_CLASS_NAME="Main.java";

    private static final String GLOBAL_JAVA_Code_PATH="tmpCode";

    private static final long TIME_OUT = 5000L;

    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final Boolean FIRST_INIT=true;

    private static final WordTree WORD_TREE;

    static {
        // 初始化字典树
        WORD_TREE = new WordTree();
        WORD_TREE.addWords(blackList);
    }



    public static void main(String[] args) {
        //拿testCode.simpleComputeArgs/Main.java测试
        JavaDockerCodeSandboxOld javaNativeCodeSandbox = new JavaDockerCodeSandboxOld();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2","3 4"));
        executeCodeRequest.setLanguage("java");
        //ResourceUtil.readStr:从resource目录下读文件
        String code= ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java",StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        ExecuteCodeResponse executeCodeResponse= javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }



    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        //1.获取请求的内容
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();

        //拿到项目根目录
        String userDir=System.getProperty("user.dir");
        //项目根目录下的tmpCode目录：全局代码目录
        String globalCodePathName=userDir+ File.separator+GLOBAL_JAVA_Code_PATH;
        //判断全局代码目录是否存在
        if(FileUtil.exist(globalCodePathName)){
            //不存在，创建
            FileUtil.mkdir(globalCodePathName);
        }

        //把用户的代码隔离存放在/tmpCode下不同文件夹
        //tmpCode下的uuid文件夹
        String userCodeParentPathName=globalCodePathName+File.separator+ UUID.randomUUID();
        //uuid文件夹下的文件
        String userCodePath=userCodeParentPathName+File.separator+GLOBAL_JAVA_CLASS_NAME;
        //把请求中的代码写入文件
        File userCodeFile=FileUtil.writeString(code,userCodePath, StandardCharsets.UTF_8);

        //2.编译代码
        //使用Process类在终端执行命令
        String compileCmd=String.format("javac -encoding utf-8 %s",userCodeFile.getAbsolutePath());
        try {
            //运行命令Runtime.getRuntime().exec(cmd命令)
            Process compileProcess= Runtime.getRuntime().exec(compileCmd);
            //等待程序执行，获取结果
            ExecuteMessage executeMessage=ProcessUtils.runProcessAndGetMessage(compileProcess,"编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            getErrorResponse(e);
        }

        //获取默认的Docker Client
        DockerClient dockerClient= DockerClientBuilder.getInstance().build();
        //3.拉取镜像
        String image="openjdk:8-alpine";
        //镜像只需要拉取一次
        if(FIRST_INIT){
            PullImageCmd pullImageCmd=dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback=new PullImageResultCallback(){
                @Override
                public void onNext(PullResponseItem item){
                    System.out.println("下载镜像："+item.getStatus());
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
        CreateContainerCmd createContainerCmd=dockerClient.createContainerCmd(image);
        HostConfig hostConfig=new HostConfig();
        hostConfig.withMemory(100*1000*1000L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeParentPathName, new Volume("/app")));  //容器挂载目录
        String profileConfig = ResourceUtil.readUtf8Str("profile.json");   //在 hostConfig 中开启安全机制：
        hostConfig.withSecurityOpts(Arrays.asList("seccomp=" + profileConfig));

        CreateContainerResponse createContainerResponse=createContainerCmd
                .withHostConfig(hostConfig)            //创建容器时，设置网络配置为关闭
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)        //限制用户不能向 root 根目录写文件
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)         //交互
                .exec();
        System.out.println(createContainerResponse);
        String containerId=createContainerResponse.getId();

        //5.启动容器
        dockerClient.startContainerCmd(containerId).exec();
        List<ExecuteMessage> executeMessageList=new ArrayList<>();

        //docker exec keen_blackwell java -cp /app Main 1 3
        //遍历执行输入用例
        for(String inputArgs:inputList){
            StopWatch stopWatch=new StopWatch();
            //创建运行class文件命令
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();

            System.out.println("创建的执行命令："+execCreateCmdResponse);

            ExecuteMessage executeMessage=new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            long time=0L;


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
                        errorMessage[0] =new String(frame.getPayload());
                        System.out.println("输出错误结果：" + new String(frame.getPayload()));
                    } else {
                        message[0] =new String(frame.getPayload());
                        System.out.println("输出结果：" + new String(frame.getPayload()));
                    }
                    super.onNext(frame);
                }
            };

            final long[]maxMemory ={0L};

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
                time=stopWatch.getLastTaskTimeMillis();
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setMessage(message[0]);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);

        }


        //4.从executeMessageList收集整理输出结果给ExecuteCodeResponse
        ExecuteCodeResponse executeCodeResponse=new ExecuteCodeResponse();
        List<String> outputList=new ArrayList<>();
        //遍历循环executeMessageList，取出message
        //获取所有用例中执行时间最长的time
        long maxTime=0;
        for(ExecuteMessage executeMessage:executeMessageList){
            //错误的信息
            String errorMessage=executeMessage.getErrorMessage();
            if(StrUtil.isNotBlank(errorMessage)){
                executeCodeResponse.setMessage(errorMessage);
                //执行存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            //正确的加入到outputList
            outputList.add(executeMessage.getMessage());
            Long time=executeMessage.getTime();
            if(time!=null){
                maxTime=Math.max(maxTime,time);
            }
        }
        //如果正常执行完成
        if(outputList.size()==executeMessageList.size()){
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo=new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);


        //5.文件清理
        if(userCodeFile.getParentFile()!=null){
            boolean del=FileUtil.del(userCodeParentPathName);
            System.out.println("删除"+(del?"成功":"失败"));
        }


        return executeCodeResponse;
    }

    //封装一个错误处理方法，当程序抛出异常时，直接返回错误响应
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误(没等到收集运行结果呢，编译或者运行过程中出错了)
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

}
