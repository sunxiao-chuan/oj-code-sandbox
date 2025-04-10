package com.sxc.ojcodesandbox;


import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口
 */
public interface CodeSandbox {

    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
