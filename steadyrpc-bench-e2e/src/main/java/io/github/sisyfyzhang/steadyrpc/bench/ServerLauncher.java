package io.github.sisyfyzhang.steadyrpc.bench;

import java.io.PrintStream;

/**
 * 对比对象的压测服务端：{@code bench server --transport <name> <其余参数>} 把除 {@code --transport} 之外的参数原样交给它。
 *
 * <p>约定：起好之后向 {@code out} 打印一行以 {@code bench-server ready port=<p> pid=<pid>} 开头的就绪行（脚本据此判断可以开压），
 * 然后阻塞到进程收到 SIGTERM / Ctrl-C、优雅关闭后返回退出码。
 */
@FunctionalInterface
public interface ServerLauncher {

	/** @return 退出码（0 正常关闭；1 用法错误） */
	int run(String[] args, PrintStream out) throws Exception;
}
