package com.nirima.jenkins.plugins.docker;


import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.plugins.sshslaves.SSHConnector;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import com.github.dockerjava.client.model.ContainerInspectResponse;
import com.github.dockerjava.client.model.ExposedPort;
import com.github.dockerjava.client.model.Ports.Binding;


/**
 * {@link hudson.slaves.ComputerLauncher} for Docker that waits for the instance to really come up before proceeding to
 * the real user-specified {@link hudson.slaves.ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public class DockerComputerLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(DockerComputerLauncher.class.getName());


    public final ContainerInspectResponse detail;
    public final DockerTemplate template;

    public DockerComputerLauncher(DockerTemplate template, ContainerInspectResponse containerInspectResponse) {
        this.template = template;
        this.detail = containerInspectResponse;
    }

    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) throws IOException, InterruptedException {
        SSHLauncher launcher = getSSHLauncher();
        int attemptsRemaining = 4;
        while (launcher.getConnection() == null && attemptsRemaining > 0) {
			synchronized (launcher) {
				Thread.sleep(1000L * (5 - attemptsRemaining)); // sleep for a few seconds - 1, 2, 3, 4
			}
        	launcher.launch(_computer, listener);
        	if (launcher.getConnection() == null) {
        		attemptsRemaining--;
        		String message = "Failed to ssh to Docker container to install agent.";
        		if (attemptsRemaining > 0) {
        			message += " Retrying ssh agent installation " + attemptsRemaining + " more times.";
        		}
                LOGGER.log(Level.WARNING, message);
        	}
        }
        if (launcher.getConnection() == null ) {
            LOGGER.log(Level.WARNING, "Could not ssh install agent to Docker container. Closing container.");
            DockerComputer dc = (DockerComputer)_computer;
            dc.getNode().terminate();
        }
        else {
        	LOGGER.log(Level.INFO, "Launched " + _computer);
        }
    }

    public SSHLauncher getSSHLauncher() throws MalformedURLException {
        /**
         * ContainerInspectResponse{
         * id='970d68eb7410bca37ccc8ac193ae68a324f7d286012c1994dcf58a28daa76da2', created='2014-01-09T12:19:37.322591068Z',
         * path='/usr/sbin/sshd', args=[-D],
         * config=ContainerConfig{hostName=970d68eb7410, portSpecs=null, user=, tty=false, stdinOpen=false, stdInOnce=false, memoryLimit=0, memorySwap=0, cpuShares=0, attachStdin=false, attachStdout=false, attachStderr=false, env=null, cmd=[Ljava.lang.String;@658782a7, dns=null, image=jenkins-3, volumes=null, volumesFrom=, entrypoint=null, networkDisabled=false, privileged=false, workingDir=, domainName=, exposedPorts={22/tcp={}}}, state=ContainerState{running=true, pid=8032, exitCode=0, startedAt='2014-01-09T12:19:37.400471534Z', ghost=false, finishedAt='0001-01-01T00:00:00Z'}, image='0ca6c5d5135db3ffb8abfef6a0861a0d2e44b6f37a33b4012a3f2d5cc99f68e9',
         * networkSettings=NetworkSettings{ipAddress='172.17.0.58', ipPrefixLen=16, gateway='172.17.42.1', bridge='docker0', ports={22/tcp=[Lcom.github.dockerjava.client.model.PortBinding;@2392d604}}, sysInitPath='null', resolvConfPath='/etc/resolv.conf', volumes={}, volumesRW={}, hostnamePath='/var/lib/docker/containers/970d68eb7410bca37ccc8ac193ae68a324f7d286012c1994dcf58a28daa76da2/hostname', hostsPath='/var/lib/docker/containers/970d68eb7410bca37ccc8ac193ae68a324f7d286012c1994dcf58a28daa76da2/hosts', name='/prickly_turing', driver='aufs'}
         */
    	int hostPort = -1;
    	Map<ExposedPort, Binding> portBindingMap = detail.getNetworkSettings().getPorts().getBindings();
    	for (Entry<ExposedPort, Binding> portBinding : portBindingMap.entrySet()) {
    		if (22 == portBinding.getKey().getPort()) {
    			hostPort = Integer.valueOf(portBinding.getValue().getHostPort());
    			break;
    		}
    	}
    	if (hostPort == -1) {
    		throw new RuntimeException("Host port not found for the SSH port");
    	}

        URL hostUrl = new URL(template.getParent().serverUrl);
        String host = hostUrl.getHost();
        
        LOGGER.log(Level.INFO, "Creating slave SSH launcher for " + host + ":" + hostPort);

        return new SSHLauncher(host, hostPort, template.credentialsId, template.jvmOptions , template.javaPath, template.prefixStartSlaveCmd, template.suffixStartSlaveCmd);
    }
    
    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
    	LOGGER.log(Level.INFO, "Disconnecting " + computer);
    	super.beforeDisconnect(computer, listener);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        /**
         * {@inheritDoc}
         */
        public String getDisplayName() {
            return "Docker SSH Launcher";
        }

        public Class getSshConnectorClass() {
            return SSHConnector.class;
        }

        /**
         * Delegates the help link to the {@link SSHConnector}.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String n = super.getHelpFile(fieldName);
            if (n==null)
                n = Jenkins.getInstance().getDescriptor(SSHConnector.class).getHelpFile(fieldName);
            return n;
        }
    }
}