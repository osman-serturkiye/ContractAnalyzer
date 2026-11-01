package junit;

import com.ser.blueline.IProperties;
import com.ser.blueline.ISession;
import com.ser.blueline.sessionpool.IDx4SessionPool;
import com.ser.blueline.sessionpool.IDx4SessionPoolConfiguration;
import com.ser.sedna.client.bluelineimpl.SEDNABluelineAdapterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

@groovy.transform.CompileStatic
public class PoolFromProps {

    private static Properties props = null;
    private static IDx4SessionPool pool = null;

    private static String orgName;

    public static synchronized IDx4SessionPool getPool(File propertiesFile) throws IOException
    {
        if (pool != null) return pool;

        props = new Properties();
        props.load(new FileReader(propertiesFile));

        int poolSize = Integer.parseInt(props.getProperty("pool.size", "5"));
        int minSize  = Integer.parseInt(props.getProperty("pool.size.min", "1"));
        long timeout = Long.parseLong(props.getProperty("pool.timeout.idle", "3600000"));
        boolean isSSL = Boolean.parseBoolean(props.getProperty("pool.ssl", "false"));

        IDx4SessionPoolConfiguration.IBuilder cfg = IDx4SessionPoolConfiguration.builder();

        cfg.setMaxPoolSize(poolSize);
        cfg.setTimeout(timeout);
        cfg.setUseSSL(isSSL);
        cfg.setMinPoolSize(minSize);

        cfg.addCsbNode(props.getProperty("csb.server1"), Integer.parseInt(props.getProperty("csb.port1")));
        if (props.getProperty("csb.server2") != null) {
            cfg.addCsbNode(props.getProperty("csb.server2"), Integer.parseInt(props.getProperty("csb.port2")));
        }
        if (props.getProperty("csb.server3") != null) {
            cfg.addCsbNode(props.getProperty("csb.server3"), Integer.parseInt(props.getProperty("csb.port3")));
        }

        orgName = props.getProperty("csb.org");

        cfg.addSessionCredentials(
                orgName,
                props.getProperty("user.name"),
                props.getProperty("user.pass"),
                props.getProperty("user.role")
        );

        SEDNABluelineAdapterFactory blFactory = SEDNABluelineAdapterFactory.getInstance();
        IProperties itaProps = blFactory.getPropertiesInstance();

        for (String key: props.stringPropertyNames()) {
            if (key.startsWith("bl.ini.")) {
                String blkey = key.substring(7);
                int p = blkey.indexOf(".");
                if (p > 0) {
                    itaProps.setProperty(blkey.substring(0, p), blkey.substring(p + 1), props.getProperty(key));
                }
            }
        }

        cfg.setServerProperties(itaProps);

        pool = IDx4SessionPool.create(cfg.build());

        return pool;
    }

    public static Properties getProperties() {
        return props;
    }

    public static ISession getSession() throws TimeoutException
    {
        long timeout = Long.parseLong(props.getProperty("pool.timeout.busy", "30000"));
        return pool.getSession(orgName, timeout);
    }

    public static void releaseSession(ISession ses)
    {
        if (ses != null) pool.releaseSession(ses);
    }

    public static void closePool() {
        if (pool != null) pool.close();
        pool = null;
    }

}
