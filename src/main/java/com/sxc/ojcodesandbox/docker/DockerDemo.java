package com.sxc.ojcodesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.core.DockerClientBuilder;

public class DockerDemo {
    public static void main(String[] args) {
        //获取默认的Docker Clint
        DockerClient dockerClint=DockerClientBuilder.getInstance().build();
        String image="nginx:latest";
        PullImageCmd pullImageCmd=dockerClint.pullImageCmd(image);

    }


}
