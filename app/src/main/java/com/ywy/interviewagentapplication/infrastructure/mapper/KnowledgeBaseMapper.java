package com.ywy.interviewagentapplication.infrastructure.mapper;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 知识库实体到DTO的映射器
 * 使用MapStruct自动生成转换代码
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface KnowledgeBaseMapper {

    /**
     * 将知识库实体转换为知识库详情 DTO
     */
    KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity);

    /**
     * 将知识库实体列表转换为知识库详情 DTO 列表
     */
    List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities);
}


