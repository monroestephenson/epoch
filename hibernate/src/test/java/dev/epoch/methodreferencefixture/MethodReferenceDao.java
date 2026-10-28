package dev.epoch.methodreferencefixture;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.function.Function;

public class MethodReferenceDao {
    public Function<String, Query> direct(EntityManager manager) {
        return manager::createQuery;
    }
    public Function<String, String> unrelated() {
        return String::trim;
    }
}
