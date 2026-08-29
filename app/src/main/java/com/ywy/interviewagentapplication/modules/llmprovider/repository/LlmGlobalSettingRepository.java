package com.ywy.interviewagentapplication.modules.llmprovider.repository;


import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}

