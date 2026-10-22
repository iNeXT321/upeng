package com.yt.agent.agent;

public abstract class ReActAgent extends BaseAgent {



    @Override
    public String step() {
        boolean isAct = think();
        if (!isAct){
            return "Thinking complete - no action needed";
        }
        return act();

    }

    /**
     * 思考过程
     * @return 是否需要执行
     */
    public abstract boolean think();

    /**
     * 执行过程
     * @return 执行结果
     */
    public abstract String act();
}
