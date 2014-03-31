package com.nirima.jenkins.plugins.docker;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.google.common.base.Strings;
import com.kpelykh.docker.client.DockerClient;
import com.kpelykh.docker.client.DockerException;
import com.kpelykh.docker.client.model.*;
import com.trilead.ssh2.Connection;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Computer;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.security.ACL;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.NodeDescriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.ClockDifference;
import hudson.util.DescribableList;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

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

    public final String sshPort;
    
    private /*almost final*/ DescribableList<NodeProperty<?>,NodePropertyDescriptor> nodeProperties = new DescribableList<NodeProperty<?>,NodePropertyDescriptor>(Jenkins.getInstance());

    private transient /*almost final*/ Set<LabelAtom> labelSet;
    public transient DockerCloud parent;

    @DataBoundConstructor
    public DockerTemplate(String image, String labelString,
                          String remoteFs,
                          String credentialsId, String jvmOptions, String javaPath,
                          String prefixStartSlaveCmd, String suffixStartSlaveCmd,
                          boolean tagOnCompletion, String instanceCapStr, String sshPort,
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

        if (!Strings.isNullOrEmpty(sshPort)) {
            Integer.parseInt(sshPort); // validate the input
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

        ContainerConfig containerConfig = new ContainerConfig();
        containerConfig.setImage(image);
        containerConfig.setCmd(new String[]{"/usr/sbin/sshd", "-D"});
        containerConfig.setPortSpecs(new String[]{"22"});

        ContainerCreateResponse container = dockerClient.createContainer(containerConfig);

        // Launch it.. :
        // MAybe should be in computerLauncher

        Map<String, PortBinding[]> bports = new HashMap<String, PortBinding[]>();
        PortBinding binding = new PortBinding();
        binding.hostIp = "0.0.0.0";
        // in case you are running Docker in something like Vagrant and have port 22 mapped to something else...
        if (!StringUtils.isBlank(sshPort)) {
        	binding.hostPort = sshPort;
        }
        bports.put("22/tcp", new PortBinding[] { binding });

        HostConfig hostConfig = new HostConfig();
        hostConfig.setPortBindings(bports);


        dockerClient.startContainer(container.getId(), hostConfig);

        String containerId = container.getId();

        ContainerInspectResponse containerInspectResponse = dockerClient.inspectContainer(containerId);


        ComputerLauncher launcher = new DockerComputerLauncher(this, containerInspectResponse);

        return new DockerSlave(this, containerId,
                containerId.substring(0, 12),
                nodeDescription,
                remoteFs, numExecutors, mode, labelString,
                launcher, retentionStrategy, nodeProperties);

    }

    public int getNumExecutors() {
        return 1;
    }

    public String getSshPort() {
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
