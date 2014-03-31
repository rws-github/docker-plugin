package com.nirima.jenkins.plugins.docker;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Collections2;
import com.kpelykh.docker.client.DockerClient;
import com.kpelykh.docker.client.DockerException;
import com.kpelykh.docker.client.model.Container;

import hudson.Extension;
import hudson.model.*;
import hudson.model.MultiStageTimeSeries.TimeScale;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nullable;
import javax.servlet.ServletException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by magnayn on 08/01/2014.
 */
public class DockerCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(DockerCloud.class.getName());

    public static final String CLOUD_ID_PREFIX = "docker-";

    public final List<? extends DockerTemplate> templates;
    public final String serverUrl;

    private transient DockerClient connection;

    @DataBoundConstructor
    public DockerCloud(String name, List<? extends DockerTemplate> templates, String serverUrl, String instanceCapStr) {
        super(name);
        this.serverUrl = serverUrl;

        if( templates != null )
            this.templates = templates;
        else
            this.templates = Collections.emptyList();

        readResolve();
    }

    protected Object readResolve() {
        for (DockerTemplate t : templates)
            t.parent = this;
        return this;
    }

    /**
     * Connects to Docker.
     */
    public synchronized DockerClient connect() {

        if (connection == null) {
            connection = new DockerClient(serverUrl);
        }
        return connection;

    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
        try {
        	LOGGER.log(Level.INFO, "Excess workload: " + excessWorkload);

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

            final DockerTemplate t = getTemplate(label);
            int containersToCreate = Math.min(excessWorkload + t.minIdleContainers, t.instanceCap);
        	LOGGER.log(Level.INFO, "Creating " + containersToCreate + " containers...");

            while (containersToCreate > 0) {
            	boolean provisioned = provisionContainer(t, r);
            	if (!provisioned) {
            		break;
            	}
                containersToCreate -= t.getNumExecutors();
            }
            return r;
        }
        catch (Exception e) {
            LOGGER.log(Level.WARNING,"Failed to provision Docker slave", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Provision a container slave.
     * @param t
     * @param r
     * @return true if the slave could be provisioned, false if it could not and no other can be at this time.
     */
    private boolean provisionContainer(final DockerTemplate t, List<NodeProvisioner.PlannedNode> r)
    throws Exception {
        if (!addProvisionedSlave(t.image, t.instanceCap)) {
            return false;
        }

        r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        // TODO: record the output somewhere
                        try {
                            DockerSlave s = t.provision(new StreamTaskListener(System.out, Charset.defaultCharset()));
                            Jenkins.getInstance().addNode(s);
                            // EC2 instances may have a long init script. If we declare
                            // the provisioning complete by returning without the connect
                            // operation, NodeProvisioner may decide that it still wants
                            // one more instance, because it sees that (1) all the slaves
                            // are offline (because it's still being launched) and
                            // (2) there's no capacity provisioned yet.
                            //
                            // deferring the completion of provisioning until the launch
                            // goes successful prevents this problem.
                            s.toComputer().connect(false).get();
                            return s;
                        }
                        catch(Exception ex) {
                            LOGGER.log(Level.WARNING, "Error in provisioning");
                            ex.printStackTrace();
                            throw Throwables.propagate(ex);
                        }
                        finally {
                            //decrementAmiSlaveProvision(t.ami);
                        }
                    }
                })
                , t.getNumExecutors()));
        return true;
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label)!=null;
    }

    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if(t.image.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link SlaveTemplate} that has the matching {@link Label}.
     */
    public DockerTemplate getTemplate(Label label) {
        for (DockerTemplate t : templates) {
            if(label == null || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Check not too many already running.
     *
     */
    private boolean addProvisionedSlave(String image, int instanceCap) throws Exception {
        if( instanceCap == 0 )
            return true;

        List<Container> containers = connect().listContainers(false);

        Collection<Container> matching = Collections2.filter(containers, new Predicate<Container>() {
            public boolean apply(@Nullable Container container) {
                // TODO: filter out containers not of the right type.
                return true;
            }
        });

        return matching.size() < instanceCap;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Docker";
        }

        public FormValidation doTestConnection(
                @QueryParameter URL serverUrl
                ) throws IOException, ServletException, DockerException {

            DockerClient dc = new DockerClient(serverUrl.toString());
            dc.info();

            return FormValidation.ok();
        }
    }

	void containerTerminated(DockerTemplate template, DockerSlave dockerSlave, TaskListener listener) {
		if (template.getMinIdleContainers() >= 0) {
			// if we have a number of min idle containers, create them
			Label label = Label.get(dockerSlave.getLabelString());
			LoadStatistics stat = label.loadStatistics;
			NodeProvisioner provisioner = label.nodeProvisioner;
			synchronized (provisioner) {
				try {
					// There may be a rounding error here. Jenkins is doing some odd stuff.
					int excessWorkload = (int) Math.min(stat.queueLength.getLatest(TimeScale.SEC10), stat.computeQueueLength());
					excessWorkload -= label.getIdleExecutors();
					// This turned into a total hack.
					Field pendingLaunchesField = provisioner.getClass().getDeclaredField("pendingLaunches");
					pendingLaunchesField.setAccessible(true);
					List<PlannedNode> plannedNodes = (List<PlannedNode>) pendingLaunchesField.get(provisioner);
					for (PlannedNode plannedNode : plannedNodes) {
						excessWorkload -= plannedNode.numExecutors;
					}
					if (excessWorkload > -template.getMinIdleContainers()) {
		                plannedNodes.addAll(provision(label, excessWorkload));
					}
				}
				catch (Exception e) {
					LOGGER.log(Level.WARNING,
							"Error in provisioning Docker container to maintain minimum idle count: " + e.toString());
				}
			}
		}
		
	}
}