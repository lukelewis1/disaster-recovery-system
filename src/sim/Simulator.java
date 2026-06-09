package sim;
import solution.DisasterResponder;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import util.ConfigurationInfo;
import util.Pair;

public class Simulator {
    private BlockingQueue<SimEvent> inEventQueue; // incoming events from TickEngine
    private BlockingQueue<SimEvent> outEventQueue; // outgoing events to TickEngine
    private BlockingQueue<Message> inMessageQueue;  // incoming messages from Responder
    private BlockingQueue<Message> outMessageQueue;  // outgoing messages to Responder (relaying from TickEngine)

    private int MESSAGE_CAPACITY = 100;
    private Thread messageThread; // handles incoming Messages (from Responder)
    private Thread eventThread; // handles incoming Events (from TickEngine)
    private TickEngine tickEngine;
    private DisasterResponder responder;

    private static final String CFG_FILE = "cfg/sim.cfg";
    private volatile boolean running = true;
    private int grandTotalPeople = 0;
    private int totalPeopleSaved = 0;
    private boolean stdoutMessages = false;

    public static void main(String[] args) {
        try {
            Simulator sim = new Simulator();
            sim.start();
        }
        catch(RuntimeException rte) {
            rte.printStackTrace();
        }
    }

    Simulator() {
        Properties cfg = ConfigurationInfo.loadConfig(CFG_FILE);
        stdoutMessages = Boolean.parseBoolean(cfg.getProperty("STDOUT_MESSAGES", "false"));
        int seed = Integer.parseInt(cfg.getProperty("SEED", "54678956"));

        System.out.print("Creating DisasterResponder....");
        String className = cfg.getProperty("RESPONDER_CLASS");
        try {
//            responder = (DisasterResponder) Class.forName(className)
//                    .getDeclaredConstructor(String.class).newInstance(CFG_FILE);

            responder = (DisasterResponder) Class.forName(className).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("DONE.");

        System.out.print("Linking incoming message queue to communicate with DisasterResponder....");
        inMessageQueue = new LinkedBlockingQueue<Message>(MESSAGE_CAPACITY);
        responder.setOutMessageQueue(inMessageQueue);
        System.out.println("DONE.");

        System.out.print("Linking outgoing message queue to communicate with DisasterResponder....");
        outMessageQueue = responder.getInMessageQueue();
        System.out.println("DONE.");

        System.out.print("Creating Tick Engine....");
        tickEngine = new TickEngine(CFG_FILE, seed);
        System.out.println("DONE.");

        System.out.print("Linking incoming event queue to communicate with TickEngine....");
        inEventQueue = new LinkedBlockingQueue<SimEvent>();
        tickEngine.setOutEventQueue(inEventQueue);
        System.out.println("DONE.");

        System.out.print("Linking outgoing event queue to communicate with TickEngine....");
        setOutEventQueue(tickEngine.getInEventQueue());
        System.out.println("DONE.");

        System.out.print("Creating thread to communicate with TickEngine....");
        eventThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running) {
                        SimEvent msg = inEventQueue.take();
//                        System.out.println(tickEngine.current_tick + ": SIMULATOR received a message from TickEngine: " + msg);
                        if (msg == SimEvent.SHUTDOWN) {
                            shutdown();
                        }
                        else {
                            updateResponder(msg);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (Exception e) {
                    System.out.println("Uncaught exception on eventThread:");
                    e.printStackTrace();
                    shutdown();
                }
            }

            void updateResponder(SimEvent se) throws Exception {
                Message m = null;
                if (se instanceof VehicleArrived) {
                    VehicleArrived va = (VehicleArrived)se;
                    m = MessageProcessor.vehicleArrived(va.getVehicleNo(), va.getLocation());
                }
                else if (se instanceof VehicleDestroyed) {
                    VehicleDestroyed vd = (VehicleDestroyed)se;
                    m = MessageProcessor.vehicleDestroyed(vd.getVehicleNo(), vd.getLocation(), vd.getNumPeople());
                }
                else if (se instanceof PeoplePickedUp) {
                    PeoplePickedUp ppu = (PeoplePickedUp) se;
                    m = MessageProcessor.peopleTransferred(ppu.getLocation(), ppu.getVehicleNo(), ppu.getNumPeople());
                }
                else if (se instanceof VehicleReturned) {
                    VehicleReturned vr = (VehicleReturned) se;
                    m = MessageProcessor.vehicleReturned(vr.getVehicleNo(), vr.getNumPeople());
                }
                else if (se instanceof VehicleHalted) {
                    VehicleHalted vh = (VehicleHalted) se;
                    m = MessageProcessor.vehicleHalted(vh.getVehicleNo(), vh.getLocation());
                }
                else if (se instanceof RoadUpdate) {
                    RoadUpdate ru = (RoadUpdate) se;
                    m = MessageProcessor.roadStatus(ru.getSource(), ru.getDestination(), ru.isOpen());
                }
                else if (se instanceof RescueRequest) {
                    RescueRequest rr = (RescueRequest) se;
                    m = MessageProcessor.rescueRequest(rr.getTarget(), rr.getNumPeople());
                }
                else if (se instanceof BuildingCollapse) {
                    BuildingCollapse bc = (BuildingCollapse) se;
                    m = MessageProcessor.locationCollapsed(bc.getTarget());
                }
                else if (se instanceof InvalidWaypoints) {
                    InvalidWaypoints ipw = (InvalidWaypoints) se;
                    m = MessageProcessor.invalidWaypoint(ipw.getVehicleNo(), ipw.getSource(), ipw.getDestination(), ipw.exists());
                }
                else if (se instanceof InvalidPathRequest) {
                    InvalidPathRequest ipr = (InvalidPathRequest) se;
                    m = MessageProcessor.invalidPathRequest(
                            ipr.getVehicleNo(), ipr.isMoving(), ipr.isDestroyed(), ipr.isNotAtStart(), ipr.isNonexistent());
                }
                if (m!= null) {
                    outMessageQueue.put(m);
                    if (stdoutMessages) {
                        System.out.println(tickEngine.current_tick + ": RESPONDER->SIMULATOR: " + m);
                    }
                }
            }
        }, "Simulator-EventThread");
        System.out.println("DONE.");

        System.out.print("Creating thread to communicate with DisasterResponder....");
        messageThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running) {
                        Message message = inMessageQueue.take();
                        updateSchedule(message);
                    }
                }
                catch(InterruptedException ioe) {
                    // this here because IOE is how it is supposed to terminate
                }
                catch (Exception e) {
                    System.out.println("Uncaught exception on messageThread:");
                    e.printStackTrace();
                    shutdown();
                }
                System.out.println("SIMULATOR MESSAGE THREAD HAS TERMINATED");
            }

            void updateSchedule(Message c) throws InterruptedException {
                if (stdoutMessages) {
                    System.out.println(tickEngine.current_tick + ": RESPONDER->SIMULATOR: " + c);
                }
                String t = c.text;
                if (t.startsWith("PATH")) {
                    Pair<Integer, List<String>> p = MessageProcessor.parseVehiclePath(t, outMessageQueue);
                    int vehicleNo = p.first();
                    List<String> waypoints = p.second();
                    VehiclePath path = new VehiclePath(vehicleNo, waypoints);
                    // now put it on the TE queue
                    outEventQueue.put(path);
                }
                else if (t.startsWith("HALT")) {
                    int vehicleNo = MessageProcessor.parseVehicleHalt(t, outMessageQueue);
                    VehicleHaltRequest vhr = new VehicleHaltRequest(vehicleNo);
                    outEventQueue.put(vhr);
                }
                else {
                    String errorMessage = "Invalid Request: " + t;
                    outMessageQueue.put(MessageProcessor.errorMessage(errorMessage) );
                }
            }
        }, "Simulator-DispatchThread");
        System.out.println("DONE.");
    }

    void setOutEventQueue(BlockingQueue<SimEvent> outEventQueue) {
        this.outEventQueue = outEventQueue;
    }

    private void shutdown() {
        try {
            tickEngine.shutdown();
            running = false;
            messageThread.interrupt();
            outMessageQueue.put(Message.SHUTDOWN);
        }
        catch (InterruptedException ioe) {
            ioe.printStackTrace();
        }
    }


    public void start() {

        eventThread.start();

        Thread tickEngineThread = new Thread(tickEngine, "Tick Engine");
        tickEngineThread.setUncaughtExceptionHandler((t, e) -> {
            System.err.println("Exception in thread " + t.getName() + ": " + e.getMessage());
        });
        tickEngineThread.start();

        messageThread.start();
        responder.start(CFG_FILE);
    }

}
