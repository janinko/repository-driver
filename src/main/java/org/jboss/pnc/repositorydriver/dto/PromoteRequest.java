package org.jboss.pnc.repositorydriver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.repositorydriver.constants.BuildType;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Getter
@AllArgsConstructor
@Jacksonized
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromoteRequest {

    private String buildContentId;
    private BuildType buildType;
    private boolean tempBuild;
    private Request callback;
    private Request heartBeat;
}
