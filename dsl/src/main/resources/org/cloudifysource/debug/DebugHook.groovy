/*******************************************************************************
 * Copyright (c) 2012 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
 package org.cloudifysource.debug

import org.cloudifysource.dsl.context.ServiceContext
import org.cloudifysource.dsl.utils.ServiceUtils;

import groovy.util.logging.*
import com.j_spaces.kernel.Environment

import java.util.logging.Logger

class DebugHook{
    private ServiceContext context
    private Logger eventLogger
    private String debugMode
    private String serviceDir
    private String serviceName
    private String groovyDebugCommandsFile
    private String preparationScript
    private String keepaliveFilename
    private List<Map<String, String>> bashCommands
    private final String javaDebugParams = '-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=10000'
    private String waitForFinishLoop
    private boolean isGroovyScript
    private String scriptName
    private File gsHomeDir

     //mode is one of:
	 // #instead - just create debug environment instead of running the target script
	 // #after - run the target script and then let me debug the outcome state
	 // #onError - like 'after', but only stop for debugging if the script fails
	 //A current limitation is that the mode for all hooks on the machine needs to be the same
	 //(since the preparation is performed before deployment)
	 DebugHook(serviceContext, mode="instead") {
		 if(!ServiceUtils.isLinuxOrUnix()) {
			 return;
		 }
		
		 this.context = serviceContext
 
		 def loggerName = "org.cloudifysource.usm.USMEventLogger.${context.getApplicationName()}.${context.getServiceName()}"
		 this.eventLogger = java.util.logging.Logger.getLogger(loggerName)
 
		 this.serviceName = context.getServiceName()
		 this.serviceDir = context.getServiceDirectory()
		 //a script in the service directory to wrap access to the DebugCommands class
		 this.groovyDebugCommandsFile = [this.serviceDir, "debug.groovy"].join(File.separator)
 
		 this.debugMode = mode
         this.keepaliveFilename = [this.serviceDir, ".cloudify_debugging.lock"].join(File.separator)

         this.gsHomeDir = new File(Environment.getHomeDirectory())

         //this script accepts a lifecycle event script as arguments
		 //and prepares the debug environment for it
		 this.preparationScript = ("""\
#! /bin/bash
# Generated by the Cloudify debug subsystem

SERVICEDIR=\"${this.serviceDir}\"
SERVICENAME=\"${this.serviceName}\"

#preserve the env variables
touch \"\$SERVICEDIR/.cloudify_env\"
printenv | grep -v -e \"^SSH\" -e \"^_\" | sed -r -e 's/^([^=]+)=(.*)\$/export \\1=\"\\2\"/' > \$SERVICEDIR/.cloudify_env
#and the DEBUG_TARGET
export DEBUG_TARGET=\"\$@\"
echo \$DEBUG_TARGET >\$SERVICEDIR/.debug_target

#import extra ssh public key(s) for the debugging connection
cd \$SERVICEDIR
if [[ -f ./debugPublicKey ]] && \
   grep -vxF -f \$HOME/.ssh/authorized_keys ./debugPublicKey #there's at least one new key to add
then
    logger -it \"CloudifyDebug\"  \"Adding public key from \$SERVICEDIR/debugPublicKey \"
    cat ./debugPublicKey >>\$HOME/.ssh/authorized_keys
fi

#chmod the actual debug target script if it's a regular file
[[ -f \$1 ]] && chmod +x \$1

#set up the 'debug' alias to enter the debug shell
if ! grep 'debugrc' \$HOME/.bashrc &>/dev/null
then
    cp \$HOME/.bashrc \$HOME/.bashrc.bak.cloudify
    echo >>\$HOME/.bashrc 'echo A cloudify debug shell is available for you by typing \"debug\"'
    cat - >>\$HOME/.bashrc <<EOS
debug() {
    SERVICE=\"\\\$1\"
    if [[ -z \"\\\$SERVICE\" ]]; then
        \\ls -1 ~/.gigaspaces/debug_sessions
    else
        SERVICEDIR=\"\\\$(cat ~/.gigaspaces/debug_sessions/\\\$SERVICE)\"
        bash --rcfile \\\$SERVICEDIR/.debugrc
    fi
}
EOS
fi
""")
	    //The contents of additional bash files that will be created for the debug environment:
	   this.waitForFinishLoop = ("""\
touch ${this.keepaliveFilename}
logger -it \"CloudifyDebug\" \"Beginning debug loop of \$1, until deletion of ${this.keepaliveFilename}\"
while [[ -f ${this.keepaliveFilename} ]]; do
    echo \"The service \$USM_SERVICE_NAME (script \$1) is waiting to be debugged on \$CLOUDIFY_AGENT_ENV_PUBLIC_IP.\"
    echo \"When finished, delete the file \$KEEPALIVE_FILE (or use the 'finish' debug command)\"
    logger -it \"CloudifyDebug\" \"Still debugging \$1\"
    sleep 5
done

logger -it \"CloudifyDebug\" \"${this.keepaliveFilename} was deleted - debug of \$1 finished\"
rm ~/.gigaspaces/debug_sessions/\$SERVICENAME
if [[ -z \"\$(ls -A ~/.gigaspaces/debug_sessions/)\" ]]; then # no more debug sessions
    [[ -f \$HOME/.bashrc.bak.cloudify ]] && mv \$HOME/.bashrc.bak.cloudify \$HOME/.bashrc
fi
""")
   }

	private String groovyDebugCommandsWrapper = ("""#! /usr/bin/env groovy
//Generated by the Cloudify debug subsystem

import org.cloudifysource.debug.DebugCommands
DebugCommands.debug(args)
""")
 
	private String debugrcTemplate = ('''\
# Generated by the Cloudify debug subsystem

#get some variables from the groovy debugHook
export SERVICEDIR=\"${serviceDir}\"
export SERVICESCRIPT=\"${serviceScript}\"
export KEEPALIVE_FILE=\"${keepaliveFile}\"
export DEBUG_GROOVY=\"${groovyDebugCommandsFile}\"
export JSHOMEDIR=\"${gsHomeDir}\"

echo Loading the debug environment...

#load the debug_target for this lifecycle event
export DEBUG_TARGET=\"`cat \\\$SERVICEDIR/.debug_target`"

#all the commands below should run from within this dir
cd \\\$SERVICEDIR

#load cloudify environment variables saved for this lifecycle event
source .cloudify_env

export CLASSPATH=`find \\\$JSHOMEDIR/lib/{required,platform/cloudify,platform/usm,platform/sigar} -name *.jar | paste -sd:`

export PATH=\\\$JSHOMEDIR/tools/groovy/bin:\\\$PATH
chmod +x \\\$DEBUG_GROOVY

#the bash command aliases:
<% bashCommands.each{
    if (it.type == "function") {
%>
function ${it.name} () {
${it.command}
}
<% } else { %>
alias ${it.name}=\'${it.command}\'
<% }
} %>

#set up shortcut aliases
if [[ ! -f debug_commands ]] ; then
    \\\$DEBUG_GROOVY --help | tail -n+2 >debug_commands
<% bashCommands.each{
    println(sprintf("echo >>debug_commands \'      %-26s%s\' ; ", it.name, it.comment))
} %>
fi

for COMMAND in `grep -Eo \'\\\\-\\\\-[^ ]*\' debug_commands | cut -c3- `; do
    alias \\\$COMMAND=\"\\\$DEBUG_GROOVY --\\\$COMMAND\"
done
#some special treatment for the help alias
alias help=\"cut -c7- <\\\$SERVICEDIR/debug_commands\"

clear
PS1=\"Debugging[\\\$DEBUG_TARGET]: \"
echo -en \"\\\\e[0;36m" #change to cyan
echo Starting a debugging session for hook \\\$DEBUG_TARGET
echo These are the available debug commands:
help
echo -en "\\\\e[0m" #reset
echo
echo "When you are done, type 'end-debug' to end the debug session and resume Cloudify's flow"
''')


    private List<Map<String, String>> getBashCommands() {
        if (! this.bashCommands.is(null)) { return this.bashCommands }

        //These are the debug commands that can run from bash,
        //as opposed to those available from the debug groovy class
        def tmpBashCommands = [
                [name:"run-script", comment:"Run the current script",
                        command:'cd $SERVICEDIR; $DEBUG_TARGET'],
                [name:"edit-script", comment:"Edit the current script", type: "function",
                        command:"""\
if [[ -z \"\$EDITOR\" ]]; then
    if `which vim` >/dev/null; then
        EDITOR=vim
    elif `which vi` >/dev/null; then
        EDITOR=vi
    else
        EDITOR=nano
    fi
fi
if [[ -f \"\$SERVICESCRIPT\" ]]; then
    \$EDITOR \"\$SERVICESCRIPT\"
elif [[ -f \"\$SERVICEDIR/\$SERVICESCRIPT\" ]]; then
    \$EDITOR "\$SERVICEDIR/\$SERVICESCRIPT\"
else
    echo \"Can't find script \$SERVICESCRIPT, perhaps the lookup path is wrong\"
fi
"""
                ],
                [name:"launch-groovysh", comment:"Launch a groovy shell",
                        command:"\$JSHOMEDIR/tools/groovy/bin/groovysh -q"],
                [name:"finish", comment:"Finish debugging (move on to the next lifecycle event)",
                        command:'rm \$SERVICEDIR/debug_commands ; rm \$KEEPALIVE_FILE && exec echo -e "Debug step finished\n"'],
        ]
        if (this.isGroovyScript) {
            tmpBashCommands += [name: "debug-script", type: "function",
                    comment: "Run the script with remote debugging enabled, will pause until IDE connects",
                    command:"""\
cd \$SERVICEDIR
if [[ -n \"\${JAVA_OPTS}\" ]]; then
    JAVA_DEBUG_OPTS=\"\${JAVA_OPTS} ${this.javaDebugParams}\"
else
    JAVA_DEBUG_OPTS=\"${this.javaDebugParams}\"
fi
env JAVA_OPTS=\"\${JAVA_DEBUG_OPTS}\" \$DEBUG_TARGET
"""
            ]
        }
        this.bashCommands = tmpBashCommands
        return this.bashCommands
    }

	 //Variants of the debug hook accessible from script dsl
	 def debug(String  arg) { return debug([arg]) }
	 def debug(GString arg) { return debug([arg.toString()],) }
 
	 //The main hook function
	 def debug(List<String> args) {
		 if(!ServiceUtils.isLinuxOrUnix()) {
			 throw new RuntimeException("Debug hooks not implemented for this O/S");
		 }
         def tmpArgs
         if (args[0].endsWith("groovy")) { // looks like a groovy script
             tmpArgs = args[1..-1]
             this.isGroovyScript = true
         } else {
             tmpArgs = args
             this.isGroovyScript = false
         }
         this.scriptName = tmpArgs.find { arg ->
            new File(arg).exists() || new File(this.serviceDir, arg).exists()
         }


		 prepareDebugRc(args.join(" "))
		 prepareGroovyDebugCommands()
         registerDebugSession()

		 def debugScriptContents = this.preparationScript
		 switch (this.debugMode) {
			 case "instead":
				 debugScriptContents += this.waitForFinishLoop
				 break
			 case "after":
				 debugScriptContents += './$@ \n' + this.waitForFinishLoop
				 break
			 case "onError":
				 debugScriptContents += './$@ && exit 0 \n' + this.waitForFinishLoop
				 break
			 default:
				 throw new RuntimeException("Unrecognized debug mode (${this.debugMode}), please use one of: 'instead', 'after' or 'onError'")
				 break
			 }
 
		 def debughookScriptName = [System.properties["user.home"], ".gigaspaces", "debug-hook.sh"].join(File.separator)
		 def debugScriptFile = new File(debughookScriptName)
         debugScriptFile.withWriter() {it.print(debugScriptContents)}
         debugScriptFile.setExecutable(true)
         this.eventLogger.info "IMPORTANT: A debug environment will be waiting for you on ${context.getPublicAddress()} after the instance has launched"
         return [debughookScriptName] + args
	 }

     def registerDebugSession() {
         File debugSessionsDir = new File([System.properties["user.home"], ".gigaspaces", "debug_sessions"].join(File.separator))
         if (! debugSessionsDir.exists() || ! debugSessionsDir.directory) { debugSessionsDir.mkdir() }
         new File(debugSessionsDir, this.serviceName).withWriter { it.println(this.serviceDir) }
     }

	 //create a wrapper for the groovy DebugCommands class
	 def prepareGroovyDebugCommands() {
         def debugSessionFile = new File(this.groovyDebugCommandsFile)
         debugSessionFile.parentFile.mkdirs()
         debugSessionFile.withWriter() {it.write(this.groovyDebugCommandsWrapper)}
	 }
 
	 def prepareDebugRc(debugTarget) {
		 def templateEngine = new groovy.text.SimpleTemplateEngine()
		 def preparedTemplate = templateEngine.createTemplate(this.debugrcTemplate).make(
			 [serviceDir: this.serviceDir,
              serviceScript: this.scriptName,
			  keepaliveFile: this.keepaliveFilename,
			  bashCommands: getBashCommands(),
			  javaDebugParams: this.javaDebugParams,
			  groovyDebugCommandsFile: this.groovyDebugCommandsFile,
              gsHomeDir: this.gsHomeDir
		 ])
		 def targetDebugrc = new File(this.serviceDir, ".debugrc")
		 targetDebugrc.withWriter() {it.write(preparedTemplate)}
	 }
 }
