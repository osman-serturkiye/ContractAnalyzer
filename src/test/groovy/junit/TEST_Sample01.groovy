package junit

import de.ser.doxis4.agentserver.AgentExecutionResult
import org.junit.*
import analyzer.Sample01
import process.OriginalSignedAnalyze

class TEST_Sample01 {

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
        def agent = new Sample01();

        binding["AGENT_EVENT_OBJECT_CLIENT_ID"] = "ST0cBPM_CONTRACT24bdf4e5c0-a58f-48ad-ad40-2731da821b29182026-09-30T14:41:51.784Z010"

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
