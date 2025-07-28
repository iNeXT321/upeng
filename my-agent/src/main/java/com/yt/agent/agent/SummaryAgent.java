package com.yt.agent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务完成，使用SummaryAgent对任务总结
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class SummaryAgent extends BaseAgent {
    @Override
    public String step() {
        return "";
    }

    public SummaryAgent(AgentContext  context) {


    }



}
