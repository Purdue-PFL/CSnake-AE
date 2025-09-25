package pfl.test_execute.driver;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

public class JUnit5SingleTest 
{
    public static void main(String[] args) throws Exception
    {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectUniqueId(args[0]))
            .build();
        

        Launcher launcher = LauncherFactory.create();
        launcher.execute(request);
    }    
}
