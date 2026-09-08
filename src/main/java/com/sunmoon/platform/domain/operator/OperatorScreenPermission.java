package com.sunmoon.platform.domain.operator;

public record OperatorScreenPermission(Screen screen, boolean canView, boolean canCreate, boolean canSave, boolean canDelete) {

    public boolean allows(Action action) {
        return switch (action) {
            case VIEW -> canView;
            case CREATE -> canCreate;
            case SAVE -> canSave;
            case DELETE -> canDelete;
        };
    }
}
