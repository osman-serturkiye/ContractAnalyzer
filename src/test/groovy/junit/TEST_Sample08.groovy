package junit


import analyzer.Sample07
import analyzer.Sample08
import de.ser.doxis4.agentserver.AgentExecutionResult
import org.junit.*

class TEST_Sample08 {

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
        def agent = new Sample08();

        binding["AGENT_EVENT_OBJECT_CLIENT_ID"] = "SR0aDX4SUPPORT24d2d18aae-388c-428d-bf66-0e675ec7f93d182026-01-09T09:38:47.651Z011"

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
