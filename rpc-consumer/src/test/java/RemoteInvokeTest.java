import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.feynman.rpc.api.annotation.RemoteInvoke;
import com.feynman.rpc.api.remote.UserFacade;
import com.feynman.rpc.api.service.User;
import com.feynman.rpc.core.config.RpcConsumerConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RemoteInvokeTest.TestConfig.class)
public class RemoteInvokeTest {
	 @RemoteInvoke
	 private  UserFacade uf; // 代理对象

	 @Test
	    public void testSaveUser() {
	        User user = new User();
	        user.setId(1);
	        user.setName("zzz");
	        User ret = uf.saveUser(user);

	        // 测试结果输出
	        System.out.println("ret = " + ret);
	        assertNotNull(ret);
	        assertEquals((Integer)1, ret.getId());
	        assertEquals("zzz", ret.getName());
	    }

	@Configuration
    @Import(RpcConsumerConfiguration.class)
    static class TestConfig { }
	}
