package com.intel.distml.platform;

import java.io.Serializable;
import java.util.LinkedList;

import akka.actor.*;
import akka.japi.Creator;

import com.intel.distml.api.Model;
import com.intel.distml.util.*;

public class MonitorActor extends UntypedActor {

    public static class DriverRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        boolean done;
        public DriverRequest() {
            done = false;
        }

        public String toString() {
            return "DriverRequest";
        }
    }

    public static class LoadModel extends DriverRequest {
        private static final long serialVersionUID = 1L;

        String path;
        public LoadModel(String path) {
            this.path = path;
        }

        public String toString() {
            return "LoadModel";
        }
    }

    public static class SaveModel extends DriverRequest {
        private static final long serialVersionUID = 1L;

        String path;
        public SaveModel(String path) {
            this.path = path;
        }

        public String toString() {
            return "SaveModel";
        }
    }

    public static class TrainingDone implements Serializable {
        private static final long serialVersionUID = 1L;

        public TrainingDone() {

        }

        public String toString() {
            return "TrainingDone";
        }
    }

    public static class RegisterResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        final public ActorRef[] parameterServers;   // parameter servers
        final public String[] psAddrs;   // parameter servers address

        public RegisterResponse(ActorRef[] parameterServers, String[] psAddrs) {
            this.parameterServers = parameterServers;
            this.psAddrs = psAddrs;
        }

        public String toString() {
            return "RegisterResponse";
        }
    }

    private final int psCount;

    private ActorRef[] parameterServers;
    private String[] psAddrs;
    private ActorRef[] serverDataBuses;

    private LinkedList<ActorRef> workers;

    private Model model;
    private ActorRef workerStarter;

    private DriverRequest pendingRequest;
    private int psCounter = 0;


    public MonitorActor(Model model) {
        this.psCount = model.psCount;
        this.parameterServers = new ActorRef[psCount];
        this.psAddrs = new String[psCount];
        this.workers = new LinkedList<ActorRef>();

        this.model = model;

        pendingRequest = null;
        psCounter = 0;

        log("Monitor created, psCount:" + psCount);
    }

    public static Props props(final Model model) {
        return Props.create(new Creator<MonitorActor>() {
            private static final long serialVersionUID = 1L;
            public MonitorActor create() throws Exception {
                return new MonitorActor(model);
            }
        });
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        log("onReceive: " + msg + ", "  + getSender() );

        if (msg instanceof PSActor.RegisterRequest) {
            PSActor.RegisterRequest req = (PSActor.RegisterRequest) msg;
            log("Parameter server registered: " + getSender());

            parameterServers[req.parameterServerIndex] = getSender();
            psAddrs[req.parameterServerIndex] = req.addr;
            psCounter++;

            log("counter: " + psCounter + ", " + psCount);
            if (psCounter == psCount) {
                model.psReady = true;
                psCounter = 0;
            }
        }
        else if (msg instanceof LoadModel) {
            pendingRequest = (DriverRequest) msg;
            String path = ((LoadModel) msg).path;
            for (int i = 0; i < parameterServers.length; i++) {
                parameterServers[i].tell(new PSActor.ModelSetup(PSActor.OP_LOAD, path), getSelf());
            }
        }
        else if (msg instanceof SaveModel) {
            pendingRequest = (DriverRequest) msg;
            String path = ((SaveModel) msg).path;
            for (int i = 0; i < parameterServers.length; i++) {
                parameterServers[i].tell(new PSActor.ModelSetup(PSActor.OP_SAVE, path), getSelf());
            }
        }
        else if (msg instanceof PSActor.ModelSetupDone) {
            psCounter++;
            if (psCounter == psCount) {
                pendingRequest.done = true;
                psCounter = 0;
            }
        }
        else if (msg instanceof WorkerActor.RegisterRequest) {
            WorkerActor.RegisterRequest info = (WorkerActor.RegisterRequest) msg;

            workers.add(getSender());
            getSender().tell(new RegisterResponse(parameterServers, psAddrs), getSelf());
        } else if (msg instanceof TrainingDone) {
            stopAll();
        }
    }

    @Override
    public void postStop() {
        log("Monitor stopped");
        getContext().system().shutdown();
    }

    private void stopAll() {
        stopActors(parameterServers);

        getContext().stop(getSelf());
        log("Start stopping monitor");
    }

    private void stopActors(ActorRef[] actors) {
        for (ActorRef ps : parameterServers) {
            ps.tell(new PSActor.Stop(), self());
        }
//        LinkedList<Future<Boolean>> stopFutures = new LinkedList<Future<Boolean>>();
//        Future<Iterable<Boolean>> stopFuture;
//        for (ActorRef actor : actors)
//            stopFutures.add(Patterns.gracefulStop(actor, Constants.STOP_FUTURE_TIMEOUT_DURATION));
//        stopFuture = sequence(stopFutures, getContext().dispatcher());
//        try {
//            // Block here to wait for termination
//            Await.result(stopFuture, Constants.STOP_FUTURE_TIMEOUT_DURATION);
//        } catch (Exception e) { // Timeout
//            Logger.error("Timeout when stopping actors. ", "Monitor");
//        }
    }

    private void log(String msg) {
        Logger.info(msg, "Monitor");
    }
}