package com.ywy.interviewagentapplication.modules.llmprovider.repository;

import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {

    /**
     * <p> findBy
     * <p> WHERE enabled = true
     * <p> OrderBy @Id Asc（升序）
     */
    List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();
}

