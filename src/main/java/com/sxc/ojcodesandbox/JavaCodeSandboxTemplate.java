package com.sxc.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;
import com.sxc.ojcodesandbox.model.ExecuteMessage;
import com.sxc.ojcodesandbox.model.JudgeInfo;
import com.sxc.ojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox{


    private static final String GLOBAL_JAVA_CLASS_NAME="Main.java";

    private static final String GLOBAL_JAVA_Code_PATH="tmpCode";

    private static final long TIME_OUT = 10000L;




    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {


        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        //1. 把用户的代码保存为文件
        File userCodeFile= saveCodeToFile(code);
        //2. 编译代码，得到 class 文件
        ExecuteMessage compileExecuteMessage=compileFile(userCodeFile);
        System.out.println(compileExecuteMessage);
        //3.执行代码，得到输出结果
        List<ExecuteMessage> executeMessageList=runFile(userCodeFile,inputList);
        //4.收集整理输出结果
        ExecuteCodeResponse outputResponse=getOutputResponse(executeMessageList);
        //5. 文件清理
        boolean b = deleteFile(userCodeFile);
        if (!b) {
            log.error("deleteFile error, userCodeFilePath = {}", userCodeFile.getAbsolutePath());
        }
        return outputResponse;
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


    /**
     * 把用户代码保存成文件
     * @param code
     * @return
     */
    public File saveCodeToFile(String code){
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
        return userCodeFile;
    }

    /**
     * 编译代码
     * @param userCodeFile
     * @return
     */
    public ExecuteMessage compileFile(File userCodeFile){
        //2.编译代码
        //使用Process类在终端执行命令
        //编译命令
        String compileCmd=String.format("javac -encoding utf-8 %s",userCodeFile.getAbsolutePath());
        try {
            //运行命令Runtime.getRuntime().exec(cmd命令)
            Process compileProcess= Runtime.getRuntime().exec(compileCmd);
            //等待程序执行，获取结果
            ExecuteMessage executeMessage= ProcessUtils.runProcessAndGetMessage(compileProcess,"编译");
            System.out.println(executeMessage);
            if(executeMessage.getExitValue()!=0){
                throw new RuntimeException("编译异常");
            }
            return executeMessage;
        } catch (IOException e) {
//            getErrorResponse(e);
            throw new RuntimeException(e);
        }
    }


    /**
     * 执行代码
     * @param userCodeFile
     * @param inputList
     * @return
     */
    public List<ExecuteMessage> runFile(File userCodeFile,List<String> inputList){
        //用于接收所有输入用例运行的结果
        List<ExecuteMessage> executeMessageList=new ArrayList<>();
        //循环遍历执行所有输入用例
        for(String inputArg:inputList){
            String runCmd=String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s",userCodeFile.getParentFile().getAbsolutePath(),inputArg);
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
                //每个输入用例的结果加入到结果list
                executeMessageList.add(executeMessage);
                System.out.println(executeMessage);
            } catch (IOException e) {
//                getErrorResponse(e);
                throw new RuntimeException("执行错误",e);
            }
        }
        return executeMessageList;
    }


    /**
     * 收集整理输出结果
     * @param executeMessageList
     * @return
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList){
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
        System.out.println("time"+maxTime);
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }

    /**
     * 文件清理
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile){
//        if(userCodeFile.getParentFile()!=null){
//            boolean del=FileUtil.del(userCodeFile.getParentFile().getAbsolutePath());
//            System.out.println("删除"+(del?"成功":"失败"));
//            return del;
//        }
        return true;

    }





}
