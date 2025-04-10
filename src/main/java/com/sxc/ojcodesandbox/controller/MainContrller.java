package com.sxc.ojcodesandbox.controller;

import com.sxc.ojcodesandbox.JavaNativeCodeSandbox;
import com.sxc.ojcodesandbox.model.ExecuteCodeRequest;
import com.sxc.ojcodesandbox.model.ExecuteCodeResponse;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


@CrossOrigin
@RestController
@RequestMapping("/")
public class MainContrller {

    // 定义鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";

    private static final String AUTH_REQUEST_SECRET = "secretKey";



    @Resource
    private JavaNativeCodeSandbox javaNativeCodeSandbox;

    /**
     * 执行代码
     *
     * @param executeCodeRequest
     * @return
     */
    @PostMapping("/executeCode")
    ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest request,
                                    HttpServletResponse response) {

        //基本的认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if(!AUTH_REQUEST_SECRET.equals(authHeader)) {
            response.setStatus(403);
            return null;
        }
        if (executeCodeRequest == null) {
            throw new RuntimeException("请求参数为空");
        }
        return javaNativeCodeSandbox.executeCode(executeCodeRequest);
    }

}
