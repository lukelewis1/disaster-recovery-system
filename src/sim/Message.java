package sim;

import java.util.List;

public class Message {
    public static final Message SHUTDOWN = new Message("SHUTDOWN");

    public String text;
    public Message(String text) {
        this.text = text;
    }

    public String toString() {
        return text;
    }
}
