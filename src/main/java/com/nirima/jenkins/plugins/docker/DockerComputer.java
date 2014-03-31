package com.nirima.jenkins.plugins.docker;

import com.google.common.base.Objects;
import com.nirima.jenkins.plugins.docker.action.DockerBuildAction;

import hudson.model.*;
import hudson.slaves.AbstractCloudComputer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by magnayn on 09/01/2014.
 */
public class DockerComputer extends AbstractCloudComputer<DockerSlave> {
    private static final Logger LOGGER = Logger.getLogger(DockerComputer.class.getName());


    private AtomicBoolean haveWeRunAnyJobs = new AtomicBoolean(false);


    public DockerComputer(DockerSlave dockerSlave) {
        super(dockerSlave);
    }

    public DockerCloud getCloud() {
        return getNode().getCloud();
    }

    public boolean haveWeRunAnyJobs() {
        return haveWeRunAnyJobs.get();
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        LOGGER.log(Level.FINE, " Computer " + this + " taskAccepted");
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    	try {
    		LOGGER.log(Level.FINE, " Computer " + this + " taskCompleted");
	        super.taskCompleted(executor, task, durationMS);

	        Queue.Executable executable = executor.getCurrentExecutable();
	        if( executable instanceof Run) {
	            Run build = (Run) executable;
	
	            if( getNode().dockerTemplate.tagOnCompletion ) {
	                getNode().commitOnTerminate( build );
	            }
	        }
    	}
    	finally {
    		haveWeRunAnyJobs.set(true);
    		getNode().retentionTerminate(); // terminate immediately, retention takes too long
    	}
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
    	try {
    		LOGGER.log(Level.FINE, " Computer " + this + " taskCompletedWithProblems");
	        super.taskCompletedWithProblems(executor, task, durationMS, problems);
    	}
    	finally {
    		haveWeRunAnyJobs.set(true);
    		getNode().retentionTerminate(); // terminate immediately, retention takes too long
    	}
    }

    @Override
    public boolean isAcceptingTasks() {
        boolean result = !haveWeRunAnyJobs() && super.isAcceptingTasks();
        LOGGER.log(Level.FINE, " Computer " + this + " isAcceptingTasks " + result);
        return result;
    }

    public void onConnected(){
        DockerSlave node = getNode();
        if (node != null) {
            node.onConnected();
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", super.getName())
                .add("slave", getNode())
                .toString();
    }
}
