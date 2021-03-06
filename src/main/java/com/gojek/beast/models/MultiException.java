package com.gojek.beast.models;

import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@AllArgsConstructor
public class MultiException extends Exception implements Status {
    private final List<Status> exceptions;

    @Override
    public boolean isSuccess() {
        return false;
    }

    @Override
    public Optional<Exception> getException() {
        return Optional.of(this);
    }

    @Override
    public String toString() {
        return "MultiException{"
                + "exceptions=" + exceptions.stream()
                .map(Object::toString).collect(Collectors.joining(","))
                + '}';
    }
}
