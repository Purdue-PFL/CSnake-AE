from typing import *
import os

#region THROW

def throw_event_inj_w_cond(prop: Dict[str, str | int | List[Dict[str, str | List[int]]]],
                           cond: List[str],
                           exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}
CLASS {class}
METHOD {method}
AT LINE {line}
IF {cond}
DO recordInjectionEvent({mapped_id});
{do_lines}
ENDRULE 
"""
    inj_class: str = prop['Class']
    inj_method: str = prop['Method']
    inj_line: int = prop['LineNumber']
    inj_id: str = prop['ExecPointID']
    exception_ctor_steps: List[Dict[str, str | List[int]]] = prop['ThrowableConstruction']

    btm_do_lines: List[str] = []
    # btm_do_lines.append("DO  recordInjectionEvent(\"{id}\");".format(id=inj_id))
    idx_type_map: Dict[int, str] = {}
    for idx, ctor_step in enumerate(exception_ctor_steps):
        step_type = ctor_step['type']
        idx_type_map[idx] = step_type
        param_list: List[int] = ctor_step['paramList']

        if idx < (len(exception_ctor_steps) - 1):
            param_list_2 = [-1] + param_list
            java_param_list = 'new int[]{' + ", ".join(str(x) for x in param_list_2) + "}"
            btm_do_line = '{prefix}createObject("{step_type}", {param_list}, {idx});'.format(**{
                'prefix': "    ", 'step_type': step_type, "param_list": java_param_list, "idx": str(idx)
            })
            btm_do_lines.append(btm_do_line)
        else:
            btm_do_line = '    ' + 'throw new ' + step_type + "("
            java_param_cast_list = []
            for param in param_list:
                java_param_cast = idx_type_map.get(param) + ".class.cast(getObject({param_idx}))".format(
                    **{"param_idx": str(param)})
                java_param_cast_list.append(java_param_cast)
            btm_do_line = btm_do_line + ", ".join(java_param_cast_list) + ");"
            btm_do_lines.append(btm_do_line)

    format_dict = {
        "id": inj_id,
        "class": inj_class,
        "method": inj_method,
        "line": str(inj_line),
        "cond": str.join(" AND ", cond),
        "do_lines": str.join(os.linesep, btm_do_lines)
    }
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[inj_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=inj_id)

    return template.format(**format_dict)


def throw_event_inj(prop: Dict[str, str | int | List[Dict[str, str | List[int]]]],
                    exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    print("WARNING: Use deprecated method throw_event_inj")
    return throw_event_inj_w_cond(prop, ['stillInject()'], exec_point_id_mapper=exec_point_id_mapper)


def throw_event_ref(prop: Dict[str, str | int | List[Dict[str, str | List[int]]]], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}
CLASS {class}
METHOD {method}
AT LINE {line}
IF true 
DO recordInjectionEvent({mapped_id});
ENDRULE
"""
    inj_class: str = prop['Class']
    inj_method: str = prop['Method']
    inj_line: int = prop['LineNumber']
    inj_id: str = prop['ExecPointID']

    format_dict = {"id": inj_id, "class": inj_class, "method": inj_method, "line": str(inj_line)}
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[inj_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=inj_id)

    return template.format(**format_dict)

#endregion THROW

#region LOOP

def loop_event_ref(prop: Dict[str, str | int], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}-Start
CLASS {class}
METHOD {method}
AT LINE {startLine}
IF true 
DO startLoop({mapped_id});
ENDRULE

RULE {id}-End
CLASS {class}
METHOD {method}
AT LINE {endLine}
IF true 
DO endLoop({mapped_id});
ENDRULE
"""
    loop_class: str = prop['Class']
    loop_method: str = prop['Method']
    loop_startLine: int = prop['loopStartEventLineNo']
    loop_endLine: int = prop['loopEndEventLineNo']
    loop_id: str = prop['LoopID']

    format_dict = {
        "id": loop_id,
        "class": loop_class,
        "method": loop_method,
        "startLine": str(loop_startLine),
        "endLine": str(loop_endLine)
    }
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[loop_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=loop_id)

    return template.format(**format_dict)


def loop_event_inj(prop: Dict[str, str | int], delayMs: int) -> str:
    template = """
RULE {id}-Start-Injection
CLASS {class}
METHOD {method}
AT LINE {startLine}
IF stillInject(100) 
DO busyLoop({delayMs});
ENDRULE
"""
    loop_class: str = prop['Class']
    loop_method: str = prop['Method']
    loop_startLine: int = prop['loopStartEventLineNo']
    loop_endLine: int = prop['loopEndEventLineNo']
    loop_id: str = prop['LoopID']

    return template.format(
        **{
            "id": loop_id,
            "class": loop_class,
            "method": loop_method,
            "startLine": str(loop_startLine),
            "endLine": str(loop_endLine),
            "delayMs": delayMs
        })

#endregion LOOP

#region BRANCH

def branch_event(prop: Dict[str, str | int], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}
CLASS {class}
METHOD {method}
AT LINE {line}
IF true 
DO visitBranch({mapped_id});
ENDRULE
"""

    branch_class: str = prop['Class']
    branch_method: str = prop['Method']
    branch_line: int = prop['LineNumber']
    branch_id: str = prop['ExecPointID']

    format_dict = {
        "id": branch_id, "class": branch_class, "method": branch_method, "line": str(branch_line)
    }
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[branch_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=branch_id)

    return template.format(**format_dict)

#endregion BRANCH

#region NEGATE

def negate_event_inj_w_cond(prop: Dict[str, str | int], cond: List[str], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}
CLASS {class}
METHOD {method}
AT EXIT
IF {cond}
DO recordInjectionEvent({mapped_id});
   return !$!;
ENDRULE
"""
    negate_class: str = prop['Class']
    negate_method: str = prop['Method']
    negate_id: str = prop['ExecPointID']

    format_dict = {
        "class": negate_class, "method": negate_method, "id": negate_id, "cond": str.join(" AND ", cond)
    }
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[negate_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=negate_id)

    return template.format(**format_dict)


def negate_event_inj(prop: Dict[str, str | int], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    print("WARNING: Use deprecated method negate_event_inj")
    return negate_event_inj_w_cond(prop, ['stillInject()'], exec_point_id_mapper=exec_point_id_mapper)


def negate_event_ref(prop: Dict[str, str | int], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    template = """
RULE {id}
CLASS {class}
METHOD {method}
AT EXIT
IF true
DO recordInjectionEvent({mapped_id});
ENDRULE
"""
    negate_class: str = prop['Class']
    negate_method: str = prop['Method']
    negate_id: str = prop['ExecPointID']

    format_dict = {"class": negate_class, "method": negate_method, "id": negate_id}
    if exec_point_id_mapper is not None:
        format_dict['mapped_id'] = exec_point_id_mapper[negate_id]
    else:
        format_dict['mapped_id'] = "\"{id}\"".format(id=negate_id)

    return template.format(**format_dict)

#endregion NEGATE

#region Utils

def loop_end_by_return_event(props: Dict[str, Dict[str, str | int]], exec_point_id_mapper: None | Dict[str, int] = None) -> str:
    method_indexed_loops: Dict[Tuple[str, str], List[str]] = {}
    for loop_key, loop_event in props.items():
        loop_class: str = loop_event['Class']
        loop_method: str = loop_event['Method']
        method_key = (loop_class, loop_method)

        if loop_method == '<clinit>':
            continue

        method_indexed_loops.setdefault(method_key, [])
        method_indexed_loops.get(method_key).append(loop_key)

    template = """
RULE {class}-{method} loop normal exits
CLASS {class}
METHOD {method}
AT EXIT
IF true
{do_lines}
ENDRULE
"""

    loop_end_btm = ""
    for (loop_class, loop_method), loop_keys in method_indexed_loops.items():
        do_lines_list: List[str] = []
        loop_keys.reverse(
        )  # Properly handle the nested loops, if the inner-most loop has a return, the loopID stack is in the reverse order
        for loop_key in loop_keys:
            prefix_str = "DO " if len(do_lines_list) == 0 else "   "
            if exec_point_id_mapper is None:
                do_line = prefix_str + "endLoop(\"{id}\");".format(id=loop_key)
            else:
                do_line = prefix_str + "endLoop({mapped_id});".format(mapped_id=exec_point_id_mapper[loop_key])
            do_lines_list.append(do_line)

        do_lines = "\n".join(do_lines_list)
        loop_end_btm = loop_end_btm + template.format(**{
            'class': loop_class, 'method': loop_method, 'do_lines': do_lines
        }) + os.linesep

    return loop_end_btm

#endregion Utils