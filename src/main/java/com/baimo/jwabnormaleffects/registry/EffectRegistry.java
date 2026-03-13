package com.baimo.jwabnormaleffects.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import com.baimo.jwabnormaleffects.config.EffectDefinition;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

public class EffectRegistry implements IEffectRegistry {
    private final Map<String, EffectDefinition> effectDefinitions = new HashMap<>();

    @Override
    public void registerEffect(EffectDefinition definition) {
        if (effectDefinitions.containsKey(definition.getId().toLowerCase())) {
            MessageUtils.log(Level.WARNING, "重复的效果ID: '" + definition.getId() + "'。");
        }
        effectDefinitions.put(definition.getId().toLowerCase(), definition);
    }

    @Override
    public Optional<EffectDefinition> getEffectDefinition(String effectId) {
        return Optional.ofNullable(effectDefinitions.get(effectId.toLowerCase()));
    }

    @Override
    public Collection<EffectDefinition> getAllEffectDefinitions() {
        return Collections.unmodifiableCollection(effectDefinitions.values());
    }

    @Override
    public void clearEffects() {
        effectDefinitions.clear();
    }

    @Override
    public boolean effectExists(String effectId) {
        return effectDefinitions.containsKey(effectId.toLowerCase());
    }

    @Override
    public boolean hasEffect(String effectId) {
        return effectExists(effectId);
    }
}