package com.sxc.ojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.sxc.ojcodesandbox.model.ExecuteMessage;
import io.netty.util.internal.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程工具类
 */
public class ProcessUtils {
     /**
      * 执行进程并获取信息
      * @param runProcess
      * @return
      */

     public static ExecuteMessage runProcessAndGetMessage(Process runProcess,String opName) {
         ExecuteMessage executeMessage = new ExecuteMessage();
         try {
              //计时
              StopWatch stopWatch = new StopWatch();
              stopWatch.start();
              //等待程序执行，获取码
              int exitValue=runProcess.waitFor();
              executeMessage.setExitValue(exitValue);
              if(exitValue==0){
                   System.out.println(opName+"成功");
                   //分批获取进程的正常输出（compileCmd命令执行完的输出就是compileProcess的输入，因为compileProcess接收了）
                   BufferedReader bufferedReader=new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
                   //用来装compileOutputLine：每行输出，最后赋值给executeMessage的message
                   List<String> compileOutputList=new ArrayList<>();
                   //逐行读取
                   String compileOutputLine;
                   while ((compileOutputLine=bufferedReader.readLine())!=null){
                        compileOutputList.add(compileOutputLine);
                   }
                   executeMessage.setMessage(StringUtils.join(compileOutputList,"\n"));
              }else{
                   //异常退出
                   System.out.println(opName+"失败，错误码:"+exitValue);
                   //分批获取进程的错误输出
                   BufferedReader errorBufferedReader=new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
                   List<String> errorCompileOutputList=new ArrayList<>();
                   //逐行读取
                   String errorCompileOutputLine;
                   while ((errorCompileOutputLine=errorBufferedReader.readLine())!=null){
                        errorCompileOutputList.add(errorCompileOutputLine);
                   }
                   executeMessage.setMessage(StringUtils.join(errorCompileOutputList,"\n"));
              }
              stopWatch.stop();
              executeMessage.setTime(stopWatch.getTotalTimeMillis());
         }catch (Exception e) {
              e.printStackTrace();
         }

         return executeMessage;

     }


     /**
      * 执行交互式进程并获取信息
      *很多 OJ都是 ACM 模式，需要和用户交互，让用户不断输入内容并获取输出，
      * 对于此类程序，我们需要使用 OutputStream 向程序终端发送参数!!!!!，并及时获取结果，注意最后要关闭流释放资源。
      * @param runProcess
      * @param args
      * @return
      */
     public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args) {
          ExecuteMessage executeMessage = new ExecuteMessage();

          try {
               // 向控制台输入程序
               OutputStream outputStream = runProcess.getOutputStream();
               OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
               String[] s = args.split(" ");
               String join = StrUtil.join("\n", s) + "\n";
               outputStreamWriter.write(join);
               // 相当于按了回车，执行输入的发送
               outputStreamWriter.flush();

               // 分批获取进程的正常输出
               InputStream inputStream = runProcess.getInputStream();
               BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
               StringBuilder compileOutputStringBuilder = new StringBuilder();
               // 逐行读取
               String compileOutputLine;
               while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(compileOutputLine);
               }
               executeMessage.setMessage(compileOutputStringBuilder.toString());
               // 记得资源的释放，否则会卡死
               outputStreamWriter.close();
               outputStream.close();
               inputStream.close();
               runProcess.destroy();
          } catch (Exception e) {
               e.printStackTrace();
          }
          return executeMessage;
     }




}
