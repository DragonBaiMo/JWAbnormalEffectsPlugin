package com.baimo.jwabnormaleffects.registry;

import java.util.Collection;
import java.util.Optional;

import com.baimo.jwabnormaleffects.config.EffectDefinition;

public interface IEffectRegistry {
    void registerEffect(EffectDefinition definition);
    Optional<EffectDefinition> getEffectDefinition(String effectId);
    Collection<EffectDefinition> getAllEffectDefinitions();
    void clearEffects();
    boolean effectExists(String effectId);
    boolean hasEffect(String effectId);
}