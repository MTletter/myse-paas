package com.myse.deployer;

/**
 * 「校验当前包」：只做本地 javac 1.8 编译校验，不上传、不安装。
 */
public class DeployCurrentFileCheckAction extends AbstractDeployCurrentFileAction {

    @Override
    protected boolean isCheckOnly() {
        return true;
    }
}
