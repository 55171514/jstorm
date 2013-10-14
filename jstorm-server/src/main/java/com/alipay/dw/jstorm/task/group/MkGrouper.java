package com.alipay.dw.jstorm.task.group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import backtype.storm.generated.Grouping;
import backtype.storm.generated.JavaObject;
import backtype.storm.grouping.CustomStreamGrouping;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Fields;
import backtype.storm.utils.Utils;

import com.alibaba.jstorm.common.JStormUtils;
import com.alibaba.jstorm.utils.RandomRange;
import com.alipay.dw.jstorm.utils.Thrift;

/**
 * Grouper, get which task should be send to for one tuple
 * 
 * @author yannian
 * 
 */
public class MkGrouper {
    private TopologyContext topology_context;
    // this component output fields 
    private Fields           out_fields;
    private Grouping         thrift_grouping;
    private Grouping._Fields fields;
    private GrouperType      grouptype;
    private List<Integer>    out_tasks;
    private List<Integer>    local_tasks;
    
    
    // grouping method
    private RandomRange      randomrange;
    private Random           random;
    private MkCustomGrouper  custom_grouper;
    private MkFieldsGrouper  fields_grouper;
    private MkLocalShuffer   local_shuffer_grouper;
    
    public MkGrouper(TopologyContext _topology_context, 
            Fields _out_fields, Grouping _thrift_grouping,
            List<Integer> _outTasks) {
        this.topology_context = _topology_context;
        this.out_fields = _out_fields;
        this.thrift_grouping = _thrift_grouping;
        
        this.out_tasks = new ArrayList<Integer>();
        this.out_tasks.addAll(_outTasks);
        Collections.sort(this.out_tasks);
        
        this.local_tasks = _topology_context.getThisWorkerTasks();
        this.fields = Thrift.groupingType(thrift_grouping);
        this.grouptype = this.parseGroupType();
        
        
        
    }
    
    public GrouperType gettype() {
        return grouptype;
    }
    
    private GrouperType parseGroupType() {
        
        GrouperType grouperType = null;
        
        if (Grouping._Fields.FIELDS.equals(fields)) {
            
            if (Thrift.isGlobalGrouping(thrift_grouping)) {
                
                // global grouping, just send tuple to first task
                grouperType = GrouperType.global;
            } else {
                
                List<String> fields_group = Thrift
                        .fieldGrouping(thrift_grouping);
                Fields fields = new Fields(fields_group);
                
                fields_grouper = new MkFieldsGrouper(out_fields, fields,
                        out_tasks);
                
                // hashcode by fields
                grouperType = GrouperType.fields;
            }
            
        } else if (Grouping._Fields.ALL.equals(fields)) {
            // send to every task
            grouperType = GrouperType.all;
        } else if (Grouping._Fields.SHUFFLE.equals(fields)) {
            this.randomrange = new RandomRange(out_tasks.size());
            grouperType = GrouperType.shuffle;
        } else if (Grouping._Fields.NONE.equals(fields)) {
            // random send one task
            this.random = new Random();
            grouperType = GrouperType.none;
        } else if (Grouping._Fields.CUSTOM_OBJECT.equals(fields)) {
            // user custom grouping by JavaObject
            JavaObject jobj = thrift_grouping.get_custom_object();
            CustomStreamGrouping g = Thrift.instantiateJavaObject(jobj);
            custom_grouper = new MkCustomGrouper(topology_context, g, out_fields, out_tasks);
            grouperType = GrouperType.custom_obj;
        } else if (Grouping._Fields.CUSTOM_SERIALIZED.equals(fields)) {
            // user custom group by serialized Object
            byte[] obj = thrift_grouping.get_custom_serialized();
            CustomStreamGrouping g = (CustomStreamGrouping) Utils
                    .deserialize(obj);
            custom_grouper = new MkCustomGrouper(topology_context, g, out_fields, out_tasks);
            grouperType = GrouperType.custom_serialized;
        } else if (Grouping._Fields.DIRECT.equals(fields)) {
            // directly send to a special task
            grouperType = GrouperType.direct;
        }else if (Grouping._Fields.LOCAL_OR_SHUFFLE.equals(fields)) {
            grouperType = GrouperType.local_or_shuffle;
            local_shuffer_grouper = new MkLocalShuffer(local_tasks, out_tasks);
            
        }
        return grouperType;
    }
    
    /**
     * get which task should tuple be sent to
     * 
     * @param values
     * @return
     */
    public List<Integer> grouper(List<Object> values) {
        if (GrouperType.global.equals(grouptype)) {
            // send to task which taskId is 0
            return JStormUtils.mk_list(out_tasks.get(0));
        } else if (GrouperType.fields.equals(grouptype)) {
            // field grouping
            return fields_grouper.grouper(values);
        } else if (GrouperType.all.equals(grouptype)) {
            // send to every task
            return out_tasks;
        } else if (GrouperType.shuffle.equals(grouptype)) {
            // random, but the random is different from none
            int rnd = randomrange.nextInt();
            return JStormUtils.mk_list(out_tasks.get(rnd));
        } else if (GrouperType.none.equals(grouptype)) {
            int rnd = Math.abs(random.nextInt()) % out_tasks.size();
            return JStormUtils.mk_list(out_tasks.get(rnd));
        } else if (GrouperType.custom_obj.equals(grouptype)) {
            return custom_grouper.grouper(values);
        } else if (GrouperType.custom_serialized.equals(grouptype)) {
            return custom_grouper.grouper(values);
        }else if (GrouperType.local_or_shuffle.equals(grouptype)) {
            return local_shuffer_grouper.grouper(values);
        }
         
        return new ArrayList<Integer>();
    }
    
}
