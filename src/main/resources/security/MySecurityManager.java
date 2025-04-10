import java.security.Permission;

public class MySecurityManager extends SecurityManager {

    // 检查所有的权限
    @Override
    public void checkPermission(Permission perm) {
//        throw new SecurityException("权限异常：" + perm.toString());
    }

    @Override
    public void checkRead(String file) {

//        throw new SecurityException("checkRead 权限异常：" + file);
    }

    @Override
    public void checkWrite(String file) {
//        throw new SecurityException("checkWrite 权限异常：" + file);
    }

    @Override
    public void checkExec(String cmd) {
        throw new SecurityException("checkExec 权限异常：" + cmd);
    }

    @Override
    public void checkConnect(String host, int port) {
//        throw new SecurityException("checkConnect 权限异常：" + host + ":" + port);
    }







}
