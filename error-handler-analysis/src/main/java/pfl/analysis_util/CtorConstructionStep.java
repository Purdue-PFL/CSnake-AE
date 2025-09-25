package pfl.analysis_util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CtorConstructionStep 
{
    public String type;
    public List<Integer> paramList = new ArrayList<>();
    public CtorConstructionStep(String type)
    {
        this.type = type;
    }
    
    public void addParam(int idx)
    {
        paramList.add(idx);
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type);
        r.put("ParamList", paramList);
        return r;
    }
}
