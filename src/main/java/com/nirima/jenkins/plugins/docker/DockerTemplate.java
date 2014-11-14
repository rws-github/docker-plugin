package com.nirima.jenkins.plugins.docker;

import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.DescribableList;
import hudson.util.StreamTaskListener;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.base.Strings;
import com.github.dockerjava.client.DockerClient;
import com.github.dockerjava.client.DockerException;
import com.github.dockerjava.client.model.ContainerConfig;
import com.github.dockerjava.client.model.ContainerCreateResponse;
import com.github.dockerjava.client.model.ContainerInspectResponse;
import com.github.dockerjava.client.model.ExposedPort;
import com.github.dockerjava.client.model.HostConfig;
import com.github.dockerjava.client.model.Ports;
import com.github.dockerjava.client.model.Ports.Binding;
import com.trilead.ssh2.Connection;

/**
 * This is not a Node. It only extends Node for Node-scoped tools.
 */
public class DockerTemplate implements Describable<DockerTemplate> {
    private static final Logger LOGGER = Logger.getLogger(DockerTemplate.class.getName());


    public final String image;
    public final String labelString;

    // SSH settings
    /**
     * The id of the credentials to use.
     */
    public final String credentialsId;

    /**
     * Field jvmOptions.
     */
    public final String jvmOptions;

    /**
     * Field javaPath.
     */
    public final String javaPath;

    /**
     * Field prefixStartSlaveCmd.
     */
    public final String prefixStartSlaveCmd;

    /**
     *  Field suffixStartSlaveCmd.
     */
    public final String suffixStartSlaveCmd;


    public final String remoteFs; // = "/home/jenkins";


    public final int instanceCap; // maximum number of containers allowed to be created at one time
    public final int minIdleContainers; // minimum number of containers to have waiting for jobs


    public final boolean tagOnCompletion;

    public final int sshPort;
    
    private /*almost final*/ DescribableList<NodeProperty<?>,NodePropertyDescriptor> nodeProperties = new DescribableList<NodeProperty<?>,NodePropertyDescriptor>(Jenkins.getInstance());

    private transient /*almost final*/ Set<LabelAtom> labelSet;
    public transient DockerCloud parent;

    @DataBoundConstructor
    public DockerTemplate(String image, String labelString,
                          String remoteFs,
                          String credentialsId, String jvmOptions, String javaPath,
                          String prefixStartSlaveCmd, String suffixStartSlaveCmd,
                          boolean tagOnCompletion, String instanceCapStr, int sshPort,
                          List<? extends NodeProperty<?>> nodeProperties, String minIdleContainersStr)
    throws IOException {
        this.image = image;
        this.labelString = Util.fixNull(labelString);
        this.credentialsId = credentialsId;
        this.jvmOptions = jvmOptions;
        this.javaPath = javaPath;
        this.prefixStartSlaveCmd = prefixStartSlaveCmd;
        this.suffixStartSlaveCmd = suffixStartSlaveCmd;
        this.remoteFs =  Strings.isNullOrEmpty(remoteFs)?"/home/jenkins":remoteFs;
        this.tagOnCompletion = tagOnCompletion;

        if (Strings.isNullOrEmpty(instanceCapStr)) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        if (Strings.isNullOrEmpty(minIdleContainersStr)) {
            this.minIdleContainers = 0;
        } else {
            this.minIdleContainers = Integer.parseInt(minIdleContainersStr);
        }

        if (this.minIdleContainers > this.instanceCap) {
        	throw new RuntimeException("Minimum number of idle containers must be less than or equals to the container cap.");
        }

        this.sshPort = sshPort;
        
        this.nodeProperties.replaceBy(nodeProperties);
        
        readResolve();
    }

    public String getInstanceCapStr() {
        if (instanceCap==Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getMinIdleContainersStr() {
        return String.valueOf(minIdleContainers);
    }

    public int getMinIdleContainers() {
        return minIdleContainers;
    }

    public Descriptor<DockerTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelSet(){
        return labelSet;
    }

    /**
     * Initializes data structure that we don't persist.
     */
    public Object readResolve() {
        labelSet = Label.parse(labelString);
        return this;
    }

    public String getDisplayName() {
        return "Image of " + image;
    }

    public DockerCloud getParent() {
        return parent;
    }

    public DockerSlave provision(StreamTaskListener listener) throws IOException, Descriptor.FormException, DockerException {
            PrintStream logger = listener.getLogger();
            DockerClient dockerClient = getParent().connect();
        logger.println("Launching " + image );

        String nodeDescription = "Docker Node";
        
        int numExecutors = 1;
        Node.Mode mode = Node.Mode.EXCLUSIVE;

        RetentionStrategy retentionStrategy = new DockerRetentionStrategy();//RetentionStrategy.INSTANCE;

        ContainerCreateResponse container = dockerClient.createContainerCmd(image)
        		.withCmd("/usr/sbin/sshd", "-D")
        		.withExposedPorts(ExposedPort.tcp(22))
        		.exec();
        String containerId = container.getId();

        // Launch it..
        boolean removeContainer = true;
        try {
	        Ports bports = new Ports();
	        bports.bind(ExposedPort.tcp(22), new Binding("0.0.0.0", sshPort));

        	dockerClient.startContainerCmd(containerId)
        		.withPortBindings(bports)
        		.exec();
        	removeContainer = false;
        }
        finally {
        	if (removeContainer) {
	            try {
	            	dockerClient.removeContainerCmd(containerId).exec();
	            }
	            catch (DockerException e) {
	                LOGGER.log(Level.SEVERE, "Failure to remove container " + containerId + " that did not start.", e);
	            }
        	}
        }

        ContainerInspectResponse containerInspectResponse = dockerClient.inspectContainerCmd(containerId).exec();

        ComputerLauncher launcher = new DockerComputerLauncher(this, containerInspectResponse);

        String nodeName = this.image + "-" + containerId.substring(0, 12);
        return new DockerSlave(this, containerId,
        		nodeName,
                nodeDescription,
                remoteFs, numExecutors, mode, labelString,
                launcher, retentionStrategy, nodeProperties);

    }

    public int getNumExecutors() {
        return 1;
    }

    public int getSshPort() {
    	return sshPort;
    }

    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        assert nodeProperties != null;
    	return nodeProperties;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<DockerTemplate> {

        @Override
        public String getDisplayName() {
            return "Docker Template";
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            return new SSHUserListBoxModel().withMatching(SSHAuthenticator.matcher(Connection.class),
                    CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context,
                            ACL.SYSTEM, SSHLauncher.SSH_SCHEME));
        }
    }

	void containerTerminated(DockerSlave dockerSlave, TaskListener listener) {
		parent.containerTerminated(this, dockerSlave, listener);
	}
}
