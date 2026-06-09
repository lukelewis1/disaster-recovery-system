package util;

import java.io.Serializable;
import java.util.Objects;

public class Pair<A, B> implements Serializable {
    protected A first = null;
    protected B second = null;

    public Pair(A a, B b) {
        this.first = a;
        this.second = b;
    }

    public A first() {
        return first;
    }

    public B second() {
        return second;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof Pair))
            return false;
        Pair o = (Pair)(obj);
        return first.equals(o.first) && second.equals(o.second);
    }
    public int hashCode() {
        return Objects.hash(first, second);
    }
}

