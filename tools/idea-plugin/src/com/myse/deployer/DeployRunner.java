package com.myse.deployer;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.awt.Desktop;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 在后台线程执行 {@code tools/deploy.py}，把输出写到日志文件，完成后弹通知。
 *
 * <p><b>为什么自己起进程而不是用 External Tools</b>：External Tools 每次只能对应一个包，
 * 多包要连点多次；这里一次调用可传多个包名（{@code deploy.py myse home coordinator}）。</p>
 *
 * <p><b>输出为什么写文件而不是 IDEA 控制台</b>：部署输出比较长（编译日志 + 上传日志），
 * 写文件便于事后回看；通知里给出文件路径。</p>
 */
public final class DeployRunner {

    private static final Logger LOG = Logger.getInstance(DeployRunner.class);

    /** 通知组 id，与 plugin.xml 的 notificationGroup 对应 */
    private static final String NOTIFICATION_GROUP = "mySE Deployer";

    /** deploy.py 相对项目根的路径 */
    private static final String DEPLOY_SCRIPT = "tools/deploy.py";

    /**
     * 解析 Python 解释器路径。
     *
     * <p>优先级：环境变量 {@code MYSE_PYTHON} → WorkBuddy 内置 Python（按平台拼路径）
     * → PATH 上的 {@code python3}/{@code python}。跨平台，不再硬编码 Windows 路径。</p>
     */
    private static String resolvePython() {
        String env = System.getenv("MYSE_PYTHON");
        if (env != null && !env.trim().isEmpty() && new File(env.trim()).isFile()) {
            return env.trim();
        }
        String home = System.getProperty("user.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] candidates = win
                ? new String[]{
                        home + "\\.workbuddy\\binaries\\python\\versions\\3.13.12\\python.exe",
                        "python.exe", "python"}
                : new String[]{
                        home + "/.workbuddy/binaries/python/versions/3.13.12/bin/python3",
                        "python3", "python"};
        for (String c : candidates) {
            if (c.indexOf('/') >= 0 || c.indexOf('\\') >= 0) {
                if (new File(c).isFile()) {
                    return c;
                }
            } else if (onPath(c)) {
                return c;
            }
        }
        return win ? "python" : "python3";
    }

    /** 在 PATH 中查找可执行文件 */
    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String ext = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? ".exe" : "";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            File f = new File(dir, exe + ext);
            if (f.isFile()) {
                return true;
            }
        }
        return false;
    }

    private DeployRunner() {
    }

    /**
     * 触发发布。
     *
     * @param project  当前项目
     * @param packages 要发布的包名列表
     * @param checkOnly 是否只做本地编译校验
     */
    public static void run(final Project project, final List<String> packages, final boolean checkOnly) {
        if (packages.isEmpty()) {
            return;
        }
        final String basePath = project.getBasePath();
        if (basePath == null) {
            showNotification("发布失败", "找不到项目根目录，无法定位 " + DEPLOY_SCRIPT, NotificationType.ERROR);
            return;
        }
        final File script = new File(basePath, DEPLOY_SCRIPT.replace('/', File.separatorChar));
        if (!script.isFile()) {
            showNotification("发布失败", "找不到部署脚本：" + script.getAbsolutePath(), NotificationType.ERROR);
            return;
        }

        new Task.Backgroundable(project, "发布 mySE 代码包：" + String.join(", ", packages), true) {
            private String logFile = null;
            private int exitCode = -1;
            private String errorMsg = null;

            @Override
            public void run(ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                List<String> cmd = new ArrayList<>();
                cmd.add(resolvePython());
                cmd.add(DEPLOY_SCRIPT);
                cmd.addAll(packages);
                if (checkOnly) {
                    cmd.add("--check-only");
                }
                cmd.add("--yes");

                logFile = writeLogHeader(basePath, cmd);
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(new File(basePath));
                    pb.redirectErrorStream(true);
                    p = pb.start();

                    // ⚠️ 日志文件打不开时不能直接抛：否则子进程 stdout 没人消费，
                    //    缓冲区满会卡死，进程永远等不到退出。改成日志降级、进程照跑。
                    Writer w = null;
                    try {
                        w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        LOG.warn("日志文件无法写入，输出将只在通知里显示", e);
                    }
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (w != null) {
                                w.write(line);
                                w.write(System.lineSeparator());
                                w.flush();
                            }
                        }
                    } finally {
                        if (w != null) {
                            try {
                                w.close();
                            } catch (Exception ignore) {
                                // 忽略关闭异常
                            }
                        }
                    }
                    exitCode = p.waitFor();
                } catch (Exception e) {
                    LOG.warn("部署进程执行失败", e);
                    errorMsg = e.getMessage();
                    appendLog(logFile, "[PLUGIN] 执行异常: " + e.getMessage());
                    if (p != null) {
                        p.destroy();
                    }
                }
            }

            @Override
            public void onFinished() {
                String title = checkOnly ? "编译校验完成" : "代码包发布完成";
                String msg;
                NotificationType type;
                if (exitCode == 0) {
                    msg = "包 " + String.join(", ", packages) + " " + (checkOnly ? "校验通过" : "发布成功")
                            + "\n日志：" + logFile;
                    type = NotificationType.INFORMATION;
                } else {
                    msg = "包 " + String.join(", ", packages) + " "
                            + (checkOnly ? "校验失败" : "发布失败")
                            + "（exit=" + exitCode + "）";
                    if (errorMsg != null) {
                        msg += "\n错误：" + errorMsg;
                    }
                    msg += "\n日志：" + logFile;
                    type = NotificationType.ERROR;
                }
                showNotification(project, title, msg, type, logFile);
            }
        }.queue();
    }

    /**
     * 生成日志文件并写入表头。
     *
     * <p><b>必须基于项目根目录，不能用相对路径</b>：IDEA 从 Dock 启动时进程 cwd 是 {@code /}，
     * 相对路径会解析成 {@code /target/deploy-logs}（macOS SIP 保护，根目录不可写），
     * 导致日志写不出来、进而连子进程都启动不了（exit=-1）。</p>
     *
     * <p>日志目录优先 {@code <项目根>/target/deploy-logs}，建不出来就退到系统临时目录。</p>
     */
    private static String writeLogHeader(String basePath, List<String> cmd) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File dir = new File(basePath, "target" + File.separator + "deploy-logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            File tmp = new File(System.getProperty("java.io.tmpdir", "/tmp"), "myse-deploy-logs");
            if (tmp.isDirectory() || tmp.mkdirs()) {
                LOG.warn("项目内日志目录不可用，改用临时目录 " + tmp.getAbsolutePath());
                dir = tmp;
            } else {
                LOG.warn("无法创建日志目录 " + dir.getAbsolutePath());
            }
        }
        File f = new File(dir, "deploy-" + ts + ".log");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("===== deploy ").append(ts).append(" =====\n");
            sb.append("cmd: ").append(String.join(" ", cmd)).append("\n");
            sb.append("cwd: ").append(basePath).append("\n\n");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (Exception e) {
            LOG.warn("写日志头失败", e);
        }
        return f.getAbsolutePath();
    }

    private static void appendLog(String logFile, String text) {
        if (logFile == null) {
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
            w.write(text);
            w.write(System.lineSeparator());
        } catch (Exception e) {
            LOG.warn("追加日志失败", e);
        }
    }

    static void showNotification(String title, String content, NotificationType type) {
        showNotification(null, title, content, type, null);
    }

    static void showNotification(Project project, String title, String content, NotificationType type) {
        showNotification(project, title, content, type, null);
    }

    /**
     * 弹通知；给了日志文件路径时额外加一个「打开日志」可点动作。
     *
     * @param project  打开日志用的项目上下文，可为 null
     * @param logFile  日志文件绝对路径，可为 null
     */
    static void showNotification(final Project project, String title, String content,
                                 NotificationType type, final String logFile) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification n = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(title, content, type);
            if (logFile != null) {
                n.addAction(NotificationAction.createSimple("打开日志", () -> openLogFile(project, logFile)));
            }
            n.notify(null);
        });
    }

    /** 打开部署日志：优先用 IDEA 内置编辑器，打不开时退回系统默认程序 */
    private static void openLogFile(Project project, String logFile) {
        try {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(logFile);
            if (vf != null && project != null && !project.isDisposed()) {
                // IDEA 2026 起 FileEditorManager.getInstance() 需要 Project 参数，
                // openTextEditor 也改成要 OpenFileDescriptor
                FileEditorManager.getInstance(project)
                        .openTextEditor(new OpenFileDescriptor(project, vf), true);
                return;
            }
        } catch (Exception e) {
            LOG.warn("在 IDEA 中打开日志失败", e);
        }
        try {
            Desktop.getDesktop().open(new File(logFile));
        } catch (Exception e) {
            LOG.warn("用系统程序打开日志失败", e);
        }
    }
}
