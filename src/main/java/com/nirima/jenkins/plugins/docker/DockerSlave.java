package com.nirima.jenkins.plugins.docker;

import hudson.Extension;
import hudson.model.Messages;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.triggers.SafeTimerTask;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import jenkins.util.Timer;

import org.acegisecurity.Authentication;
import org.apache.commons.io.FileUtils;

import com.google.common.base.Objects;
import com.github.dockerjava.client.DockerClient;
import com.github.dockerjava.client.DockerException;
import com.github.dockerjava.client.model.CommitConfig;
import com.nirima.jenkins.plugins.docker.action.DockerBuildAction;


public class DockerSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(DockerSlave.class.getName());

    public final DockerTemplate dockerTemplate;
    public final String containerId;


    private transient Run theRun;

    public DockerSlave(DockerTemplate dockerTemplate, String containerId, String name, String nodeDescription, String remoteFS, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties) throws Descriptor.FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.dockerTemplate = dockerTemplate;
        this.containerId = containerId;
    }

    /**
     * Overriding because of a bug in Jenkins that prevents Slaves from being EXCLUSIVE while the Master has a number of
     * executors = 0.
     */
    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {
        Label l = item.getAssignedLabel();
        if(l!=null && !l.contains(this))
            return CauseOfBlockage.fromMessage(Messages._Node_LabelMissing(getNodeName(),l));   // the task needs to be executed on label that this node doesn't have.

        Authentication identity = item.authenticate();
        if (!getACL().hasPermission(identity,Computer.BUILD)) {
            // doesn't have a permission
            // TODO: does it make more sense to define a separate permission?
            return CauseOfBlockage.fromMessage(Messages._Node_LackingBuildPermission(identity.getName(),getNodeName()));
        }

        // Check each NodeProperty to see whether they object to this node
        // taking the task
        for (NodeProperty prop: getNodeProperties()) {
            CauseOfBlockage c = prop.canTake(item);
            if (c!=null)    return c;
        }

        // Looks like we can take the task
        return null;
    }

    public DockerCloud getCloud() {
        return dockerTemplate.getParent();
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    public void commitOnTerminate(Run run) {
       this.theRun = run;
    }

    @Override
    public DockerComputer createComputer() {
        return new DockerComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        DockerClient client = getClient();

        try {
        	LOGGER.log(Level.INFO, "Disconnecting slave " + super.getDisplayName());
            toComputer().disconnect(null);
            try {
            	client.stopContainerCmd(containerId).exec();

                if (theRun != null) {
                    try {
                        commit();
                    }
                    catch (DockerException e) {
                        LOGGER.log(Level.SEVERE, "Failure to commit Docker container " + containerId, e);
                    }
                }
                
                try {
                	client.removeContainerCmd(containerId).exec();
                }
                catch (DockerException e) {
                    LOGGER.log(Level.SEVERE, "Failure to remove container " + containerId, e);
                }
            }
            catch (DockerException e) {
                LOGGER.log(Level.SEVERE, "Failure to stop container " + containerId, e);
            }
        }
        catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failure to disconnect, stop or remove Docker container " + containerId, e);
        }
        finally {
        	try {
	        	// delete log directory/files
	        	File slaveLogDir = new File(Jenkins.getInstance().getRootDir(), "logs/slaves/" + getDisplayName());
	        	if (slaveLogDir.exists()) {
	        		FileUtils.deleteDirectory(slaveLogDir);
	        	}
        	}
        	finally {
        		dockerTemplate.containerTerminated(this, listener);
        	}
        }
    }

    public void commit() throws DockerException, IOException {
        DockerClient client = getClient();

        String tag_image = client.commitCmd(containerId)
        	.withAuthor("Jenkins")
            .withRepository(theRun.getParent().getDisplayName())
            .withTag(theRun.getDisplayName())
            .exec();

        theRun.addAction( new DockerBuildAction(getCloud().serverUrl, containerId, tag_image) );
        theRun.save();
    }

    public DockerClient getClient() {
        return dockerTemplate.getParent().connect();
    }

    /**
     * Called when the slave is connected to Jenkins
     */
    public void onConnected() {
    	LOGGER.info("Docker provisioned slave " + getDisplayName() + " connected");
    }

    public void retentionTerminate() {
        Timer.get().submit(new SafeTimerTask() {
            public void doRun() {
            	try {
                	LOGGER.log(Level.INFO, "Terminating Docker provisioned slave " + getDisplayName());
            		terminate();
                	LOGGER.log(Level.INFO, "Terminated Docker provisioned slave " + getDisplayName());
            	}
            	catch (Exception e) {
                	LOGGER.log(Level.WARNING, "Error terminating Docker provisioned slave " + getDisplayName(), e);
            	}
            }
        });
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("containerId", containerId)
                .toString();
    }

    @Extension
	public static final class DescriptorImpl extends SlaveDescriptor {

    	@Override
		public String getDisplayName() {
			return "Docker Slave";
    	};

		@Override
		public boolean isInstantiable() {
			return false;
		}

	}
}
