package com.sxc.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;
import com.sxc.ojcodesandbox.model.ExecuteMessage;
import com.sxc.ojcodesandbox.model.JudgeInfo;
import com.sxc.ojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class JavaNativeCodeSandboxOld implements CodeSandbox {


    private static final String GLOBAL_JAVA_CLASS_NAME="Main.java";

    private static final String GLOBAL_JAVA_Code_PATH="tmpCode";

    private static final long TIME_OUT = 10000L;

    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final WordTree WORD_TREE;

    static {
        // 初始化字典树
        WORD_TREE = new WordTree();
        WORD_TREE.addWords(blackList);
    }

    private static final String SECURITY_MANAGER_PATH="D:\\project\\OJ\\oj-code-sandbox\\src\\main\\resources\\security";

    private static final String SECURITY_MANAGER_CLASS_NAME="MySecurityManager";





    public static void main(String[] args) {
        //拿testCode.simpleComputeArgs/Main.java测试
        JavaNativeCodeSandboxOld javaNativeCodeSandbox = new JavaNativeCodeSandboxOld();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2","3 4"));
        executeCodeRequest.setLanguage("java");
        //ResourceUtil.readStr:从resource目录下读文件
        String code= ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java",StandardCharsets.UTF_8);
//        String code= ResourceUtil.readStr("testCode/unsafeCode/WriteFileError.java",StandardCharsets.UTF_8);
//        String code= ResourceUtil.readStr("testCode/unsafeCode/RunFileError.java",StandardCharsets.UTF_8);

        //下面是Scanner输入参数的，例子，不用管
//        String code= ResourceUtil.readStr("testCode.simpleCompute/Main.java",StandardCharsets.UTF_8);

        executeCodeRequest.setCode(code);
        ExecuteCodeResponse executeCodeResponse= javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }



    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

//        System.setSecurityManager(new MySecurityManager());
        //获取请求的内容
        List<String> inputList = executeCodeRequest.getInputList();
        String language = executeCodeRequest.getLanguage();
        String code = executeCodeRequest.getCode();

        //  校验代码中是否包含黑名单中的禁用词
//        FoundWord foundWord = WORD_TREE.matchWord(code);
//        if (foundWord != null) {
//            System.out.println("包含禁止词：" + foundWord.getFoundWord());
//            return null;
//        }

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
        //编译命令
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

        //3.执行代码
        //用于接收所有输入用例运行的结果
        List<ExecuteMessage> executeMessageList=new ArrayList<>();
        //循环遍历执行所有输入用例
        for(String inputArg:inputList){
            String runCmd=String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s",userCodeParentPathName,inputArg);
//            String runCmd=String.format("java -Xmx256m -Dfile.encoding=UTF-8 %s;%s -Djava.security.manager=%s Main %s",
//                    userCodeParentPathName,
//                    SECURITY_MANAGER_PATH,
//                    SECURITY_MANAGER_CLASS_NAME,
//                    inputArg);
//            String runCmd=String.format("java -Dfile.encoding=UTF-8 -cp %s Main",userCodeParentPathName);
            try {
                Process runProcess=Runtime.getRuntime().exec(runCmd);
                // 超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        System.out.println("超时了，中断");
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage=ProcessUtils.runProcessAndGetMessage(runProcess,"运行");
//                ExecuteMessage executeMessage=ProcessUtils.runInteractProcessAndGetMessage(runProcess,inputArg);
                //每个输入用例的结果加入到结果list
                executeMessageList.add(executeMessage);
                System.out.println(executeMessage);
            } catch (IOException e) {
                getErrorResponse(e);
            }
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
