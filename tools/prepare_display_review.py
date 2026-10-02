from pathlib import Path
import json,sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
from source_detail_models import rotate

def prepare(home):
    details=json.loads((home/'map-details.json').read_text(encoding='utf-8'))
    selected=[];seen=set()
    special={'lost:staticprop:56','lost:staticprop:181','lost:staticprop:282'}
    for chapter in details['chapters']:
        for p in chapter['props']:
            if p['status'] not in {'placed','placed_display'}:continue
            key=(chapter['chapter'],p['mapping'],p['status'])
            variant=('variant',p['mapping'],p['model'])
            manual=bool(p['id'] in special or (p['mapping']=='fixture' and np.linalg.norm(p.get('renderPivotOffset',[0,0,0]))>1) or (p['mapping']=='showerhead' and np.linalg.norm(p.get('renderPivotOffset',[0,0,0]))>1.25) or p.get('visibilitySampleFraction',1)<.55)
            if key in seen and variant in seen and not manual:continue
            seen.update((key,variant))
            point=dict(chapter=chapter['chapter'],routeIndex=1000+len(selected),kind=p['mapping'],propId=p['id'],anchor=p['nativeAnchor'],capture=True,placementKind=p['status'],sourceAngles=p['angles'],sourceModel=p['model'],renderPivotOffset=p.get('renderPivotOffset',[0,0,0]),manualPivotReview=manual)
            if p['status']=='placed_display':
                parts=p['descriptors'];part=parts[1 if p['mapping']=='bed' else (-1 if p['mapping']=='fixture' else 0)]
                target=np.asarray(part['position'])+part['translation']+rotate(np.asarray(part['rotation']),np.asarray(part['scale'])*.5)
                point['displayUuids']=[q['uuid'] for q in parts]
            else:target=np.asarray(p['nativeAnchor'])+[.5,1.25 if p['mapping']=='table' else .65,.5]
            if p['status']=='placed':point['nativeCells']=[q['pos'] for q in p['cells']]
            point['target']=np.round(target,6).tolist();point['position']=[float(target[0]-.5),float(target[1]-1.62),float(target[2]+2.5)];point['next']=[float(target[0]-.5),float(target[1]),float(target[2]-.5)]
            selected.append(point)
    (home/'map-prepared-details.json').write_text(json.dumps(selected,indent=2)+'\n',encoding='utf-8')
    print('REVIEW_POINTS',len(selected),flush=True)

if __name__=='__main__':prepare(Path(sys.argv[1]))
