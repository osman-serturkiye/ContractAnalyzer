package junit

import analyzer.Sample01
import de.ser.doxis4.agentserver.AgentExecutionResult
import org.junit.*
import process.OriginalSignedAnalyze

class TEST_OriginalSignedAnalyze {

    Binding binding

    @BeforeClass
    static void initSessionPool() {
        AgentTester.initSessionPool()
    }

    @Before
    void retrieveBinding() {
        binding = AgentTester.retrieveBinding()
    }

    @Test
    void testForAgentResult() {
        def agent = new OriginalSignedAnalyze();

        binding["AGENT_EVENT_OBJECT_CLIENT_ID"] = "ST07BPM_CLM24ae751aeb-9ec8-40ea-bb99-e558bbffb626182026-11-16T09:24:10.216Z0214"

        def result = (AgentExecutionResult) agent.execute(binding.variables)
        assert result.resultCode == 0
    }

    @Test
    void testForJavaAgentMethod() {
        //def agent = new JavaAgent()
        //agent.initializeGroovyBlueline(binding.variables)
        //assert agent.getServerVersion().contains("Linux")
    }

    @After
    void releaseBinding() {
        AgentTester.releaseBinding(binding)
    }

    @AfterClass
    static void closeSessionPool() {
        AgentTester.closeSessionPool()
    }
}
