package junit

import com.ser.sedna.client.bluelineimpl.system.Session
import de.ser.doxis4.agentserver.AgentExecutionResult
import de.ser.doxis4.agentserver.AgentServerReturnCodes
import de.ser.doxis4.agentserver.ReceiversAgentExecutionResult
import de.ser.doxis4.agentserver.UnifiedAgent

@groovy.transform.CompileStatic
class AgentTester {

    static final File propertiesFile = new File("AgentTester.properties")

    static GroovyScriptEngine scriptEngine = null

    public static void main(String[] args) {

        def agentPath = new File("agents/src/").absolutePath
        scriptEngine = new GroovyScriptEngine(agentPath, AgentTester.class.classLoader)
        scriptEngine.config.sourceEncoding = "UTF-8"
        scriptEngine.config.recompileGroovySource = true

        initSessionPool()
        println("SessionPool and ScriptEngine ready...")
        releaseBinding(retrieveBinding())
        println("Session established, running agent ...")

        try {
            BufferedReader br = System.in.newReader()
            do {
                String className = args.length > 0 ? args[0] : "TestAgent"

                def binding = retrieveBinding()
                if (args.length > 1) {
                    binding["AGENT_EVENT_OBJECT_CLIENT_ID"] = args[1]
                }

                try {

                    def clazz = scriptEngine.groovyClassLoader.loadClass(className, true, false, false)
                    def instance = clazz.getDeclaredConstructor().newInstance()
                    Object res

                    if (instance instanceof UnifiedAgent) {
                        res = instance.execute(binding.variables)
                    }
                    else if (instance instanceof Script) {
                        instance.binding = binding
                        res = instance.run()
                    }
                    else {
                        res = "Error - neither Script nor UnifiedAgent"
                    }

                    if (res instanceof AgentExecutionResult) {
                        def aer = (AgentExecutionResult)res
                        if (aer.resultCode == AgentServerReturnCodes.RETURN_CODE_SUCCESS)
                            println("resultSucess:")
                        else
                            println(aer.restartable ? "resultRestart:" : "resultError:")
                        println(aer.executionMessage)
                        if (res instanceof ReceiversAgentExecutionResult) {
                            println(((ReceiversAgentExecutionResult)res).workbasketIds)
                        }
                    }
                    else {
                        println(res.toString())
                    }
                }
                catch (Exception ex) {
                    println(ex.class.name)
                    println(ex.getMessage())
                    println(ex.stackTrace.toString())
                }
                finally {
                    releaseBinding(binding)
                }

            }
            while (br.readLine())
        }
        finally {
            closeSessionPool()
        }
    }

    static void initSessionPool() {
        PoolFromProps.getPool(propertiesFile)
    }

    static void closeSessionPool() {
        PoolFromProps.closePool()
    }

    static Binding retrieveBinding() {

        def ses = (Session)PoolFromProps.getSession()
        def srv = ses.documentServer

        return new Binding([
                documentServer : srv,
                doxis4Session  : ses,
                sednaSession   : ses.sednaSession
        ])
    }

    static void releaseBinding(Binding binding) {
        PoolFromProps.releaseSession((Session)binding["doxis4Session"])
    }

}
