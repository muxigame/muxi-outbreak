"""Reviewed, collision-free vanilla display approximations for Source props.

Each box is a real vanilla block model. Poses retain full Source angles/scale;
bounded render-pivot offsets compensate voxel/pivot differences, never walls.
This does not reproduce Source meshes or movable prop_physics simulation.
"""
from __future__ import annotations
import math, uuid
import numpy as np

def multiply(a,b):
    x,y,z,w=a;u,v,t,s=b
    return np.asarray([w*u+x*s+y*t-z*v,w*v-x*t+y*s+z*u,w*t+x*v-y*u+z*s,w*s-x*u-y*v-z*t])

def rotation(angles):
    pitch,yaw,roll=np.radians(angles)/2
    q=multiply(multiply([0,math.sin(yaw),0,math.cos(yaw)],[0,0,-math.sin(pitch),math.cos(pitch)]),[math.sin(roll),0,0,math.cos(roll)])
    return q/np.linalg.norm(q)

def rotate(q,points):
    points=np.asarray(points,dtype=float);v=q[:3];return points+2*np.cross(v,np.cross(v,points)+q[3]*points)

def boxes(kind,model):
    # Block, local min corner, local size; local +X is Source forward.
    wood='minecraft:spruce_planks';metal='minecraft:iron_block';white='minecraft:white_wool'
    out=[]
    def box(block,pos,size):out.append((block,np.asarray(pos,float),np.asarray(size,float)))
    if kind=='bed':
        box(wood,[0,.16,-.46],[1.95,.16,.92]);box(white,[.02,.32,-.44],[1.9,.18,.88]);box('minecraft:white_concrete',[1.48,.50,-.42],[.43,.10,.84])
        for x,z in ((.08,-.38),(1.72,-.38),(.08,.29),(1.72,.29)):box(metal,[x,0,z],[.12,.32,.10])
        box(metal,[1.87,.15,-.48],[.08,.60,.96]);box(metal,[0,.15,-.48],[.06,.36,.96])
    elif kind=='chair':
        box(wood,[-.34,.40,-.34],[.68,.13,.68]);box(wood,[-.34,.48,-.34],[.10,.58,.68])
        for x,z in ((-.27,-.27),(.19,-.27),(-.27,.19),(.19,.19)):box(wood,[x,0,z],[.08,.43,.08])
    elif kind=='table':
        wide=1.7 if 'desk' in model else 1.3
        box(wood,[-wide/2,.83,-.42],[wide,.14,.84])
        for x,z in ((-wide/2+.1,-.32),(wide/2-.21,-.32),(-wide/2+.1,.21),(wide/2-.21,.21)):box(wood,[x,0,z],[.11,.86,.11])
    elif kind=='cabinet':
        tall=1.65;box(wood,[-.40,0,-.42],[.80,tall,.84]);box('minecraft:dark_oak_planks',[.402,.05,-.37],[.025,tall-.10,.74])
        for z in (-.07,.07):box(metal,[.43,.78,z],[.055,.18,.035])
    elif kind=='crate':box(wood,[-.45,0,-.45],[.90,.90,.90])
    elif kind=='shelf':
        for y in (.08,.62,1.18,1.72):box(metal,[-.55,y,-.24],[1.10,.055,.48])
        for x,z in ((-.53,-.22),(.49,-.22),(-.53,.18),(.49,.18)):box(metal,[x,0,z],[.045,1.82,.045])
    elif kind=='toolchest':
        box('minecraft:red_concrete',[-.50,.12,-.32],[1.0,.72,.64]);box(metal,[-.51,.84,-.33],[1.02,.06,.66])
        for y in (.24,.42,.60):box(metal,[.505,y,-.27],[.022,.022,.54])
        for x,z in ((-.4,-.26),(.32,-.26),(-.4,.18),(.32,.18)):box('minecraft:black_concrete',[x,0,z],[.08,.12,.08])
    elif kind in {'medical_cart','medical_gurney'}:
        length=2.05 if kind=='medical_gurney' else .95;width=.85 if kind=='medical_gurney' else .62
        box(metal,[-length/2,.70,-width/2],[length,.07,width])
        if kind=='medical_gurney':box(white,[-length/2+.04,.77,-width/2+.04],[length-.08,.12,width-.08])
        else:box(metal,[-length/2,.22,-width/2],[length,.045,width])
        for x,z in ((-length/2+.10,-width/2+.1),(length/2-.14,-width/2+.1),(-length/2+.10,width/2-.14),(length/2-.14,width/2-.14)):
            box(metal,[x,.06,z],[.04,.67,.04]);box('minecraft:black_concrete',[x-.03,0,z-.03],[.1,.09,.1])
    elif kind=='medical_pole':
        box(metal,[-.025,0,-.025],[.05,1.95,.05]);box(metal,[-.27,.02,-.03],[.54,.055,.06]);box(metal,[-.03,.02,-.27],[.06,.055,.54]);box(metal,[-.18,1.88,-.02],[.36,.035,.04])
        box('minecraft:white_stained_glass',[.1,1.40,-.07],[.12,.40,.14])
    elif kind=='sleeping_bag':box('minecraft:green_wool',[-.92,.01,-.34],[1.84,.12,.68]);box('minecraft:white_wool',[.54,.13,-.29],[.31,.06,.58])
    elif kind=='curtain':box('minecraft:light_gray_wool',[-.70,-1.85,-.035],[1.40,1.85,.07]);box(metal,[-.75,0,-.05],[1.50,.06,.1])
    elif kind=='container_box':box(wood,[-.5,0,-.5],[1.,1.,1.]);box(metal,[-.51,.38,-.51],[1.02,.07,1.02])
    elif kind=='shipping_container':
        box('minecraft:green_concrete',[-3.0,0,-1.1],[6.0,2.2,2.2]);box(metal,[3.,.08,-1.02],[.03,2.02,2.04])
    elif kind=='bathroom_sink':
        box('minecraft:white_concrete',[-.35,.64,-.29],[.70,.14,.58]);box('minecraft:light_blue_stained_glass',[-.27,.78,-.21],[.54,.012,.42]);box(metal,[-.04,.78,-.27],[.08,.22,.05]);box('minecraft:white_concrete',[-.1,0,-.1],[.20,.65,.20])
    elif kind=='toilet':
        box('minecraft:white_concrete',[-.22,0,-.24],[.44,.40,.48]);box('minecraft:white_concrete',[-.27,.40,-.29],[.54,.12,.58]);box('minecraft:light_blue_stained_glass',[-.17,.52,-.18],[.34,.012,.36]);box('minecraft:white_concrete',[-.33,.37,-.42],[.16,.54,.84])
    elif kind=='showerhead':
        box(metal,[-.02,0,-.02],[.04,.65,.04]);box(metal,[-.02,.62,-.02],[.27,.04,.04]);box(metal,[.16,.60,-.09],[.16,.03,.18])
    elif kind=='fixture':
        stem=model.rsplit('/',1)[-1].removesuffix('.mdl')
        if stem=='lamppost03a_off':
            box('minecraft:gray_concrete',[-.09,0,-.09],[.18,6.2,.18]);box(metal,[-.20,6.1,-.20],[.40,.10,.40]);box('minecraft:white_stained_glass',[-.15,6.2,-.15],[.30,.40,.30]);box(metal,[-.23,6.6,-.23],[.46,.09,.46])
        elif stem=='wall_light':
            box(metal,[-.05,-.36,-.22],[.08,.72,.44]);box('minecraft:white_stained_glass',[.03,-.31,-.18],[.08,.62,.36])
        elif stem=='surgery_lamp':
            box(metal,[-.035,-1.45,-.035],[.07,1.45,.07]);box(metal,[-.42,-1.50,-.42],[.84,.07,.84]);box('minecraft:white_stained_glass',[-.36,-1.55,-.36],[.72,.05,.72])
        elif stem=='light_floodlight':
            box(metal,[-.05,0,-.05],[.10,1.2,.10]);box(metal,[-.10,.9,-.36],[.20,.55,.72]);box('minecraft:white_stained_glass',[.10,.96,-.30],[.03,.43,.60])
        else:
            box(metal,[-.80,-.06,-.22],[1.60,.07,.44]);box('minecraft:white_stained_glass',[-.74,-.095,-.17],[1.48,.035,.34])
    return out

SAMPLES=np.asarray([[x,y,z] for x,y,z in ((0,.5,.5),(1,.5,.5),(.5,0,.5),(.5,1,.5),(.5,.5,0),(.5,.5,1),(.05,.05,.05),(.95,.95,.95),(.05,.95,.95),(.95,.05,.05))])

def visible_fraction(g,anchor,q,parts,scale):
    pts=np.concatenate([rotate(q,(p+SAMPLES*s)*scale)+anchor for block,p,s in parts])
    cells=np.floor(pts).astype(int)-g.origin
    valid=np.all(cells>=0,axis=1)&np.all(cells<g.grid.shape,axis=1)
    ids=np.zeros(len(cells),dtype=int);ids[valid]=g.grid[tuple(cells[valid].T)]
    transparent=np.asarray([s['Name'] in {'minecraft:air','minecraft:light','minecraft:ladder','minecraft:scaffolding','minecraft:light_gray_stained_glass'} for s in g.palette])
    return float(np.mean(transparent[ids]&valid))

def display_mapping(g,prop,index,transform,prior):
    kind=prior['mapping'];parts=boxes(kind,prop['model']);q=rotation(prop['angles']);scale=float(prop.get('scale',1))
    if not parts or not .1<=scale<=4:return dict(prior,status='manual_review',reason='no_safe_display_template_or_scale')
    origin=transform(prop['origin'],index)+[.5,0,.5]
    flat=abs(prop['angles'][0])<=5 and abs(prop['angles'][2])<=5
    # The anchor stays at the Source pose; these are bounded mesh-pivot adjustments.
    candidates=[np.zeros(3)]
    for distance in (.25,.5,.75,1.0):
        for x,z in ((distance,0),(-distance,0),(0,distance),(0,-distance)):
            candidates.append(rotate(q,[x,0,z]))
    if kind not in {'fixture','bed'}:
        for x in (-.5,.5,-.75,.75):
            for z in (-.5,.5,-.75,.75):candidates.append(rotate(q,[x,0,z]))
    if kind=='showerhead':
        # Reviewed shower pivots can fall inside a two-cell voxelized wall;
        # expose the same source-oriented template at its nearest native face.
        for x in (-1.25,1.25,-1.5,1.5,-1.75,1.75,-2.,2.):candidates.append(rotate(q,[x,0,0]))
    if kind=='bed':
        # Three reviewed beds overlap an intact wall with the initial foot-based
        # template. A local longitudinal pivot change converts foot to center;
        # retain the room, Source anchor and full orientation unchanged.
        for x in (-1.25,-1.5,-1.75,-2.,1.25,1.5):candidates.append(rotate(q,[x,0,0]))
        for x in (-1.,-1.25,-1.5):
            for z in (-.5,.5,-.75,.75):candidates.append(rotate(q,[x,0,z]))
        if not flat:
            for y in (-2.,-1.5,1.25,1.5,1.75,2.,2.25,2.5,2.75,3.):candidates.append(np.asarray([0,y,0]))
    for y in (-.25,.25,-.5,.5,-.75,.75,-1.,1.):candidates.append(np.asarray([0,y,0]))
    if kind=='fixture' and prop['model'].endswith('/wall_light.mdl'):
        # Manually reviewed wall-light origins can be enclosed by a whole slab
        # of voxelized inter-floor structure. Keep X/Z and source angles; expose
        # the model at the nearest vertical face rather than cutting that slab.
        for y in (-1.25,-1.5,-1.75,-2.,-2.25,-2.5,-2.75,-3.,-3.25,-3.5,-3.75,-4.,1.25,1.5,1.75,2.,2.25,2.5,2.75,3.,3.25,3.5,3.75,4.):candidates.append(np.asarray([0,y,0]))
    if kind!='fixture' and flat:
        for c in [c.copy() for c in candidates if abs(c[1])<.25]:
            anchor=origin+c;p=np.floor(anchor).astype(int)
            for dy in (0,-1,1,-2,2):
                y=int(round(origin[1]))+dy
                if g.solid((p[0],y-1,p[2])) and not g.solid((p[0],y,p[2])):
                    candidates.append(np.asarray([c[0],y-origin[1],c[2]]));break
    scored=[]
    for delta in candidates:
        visibility=visible_fraction(g,origin+delta,q,parts,scale)
        scored.append((visibility-.045*np.linalg.norm(delta),visibility,-np.linalg.norm(delta),delta))
    best=max(scored,key=lambda x:x[:3]);delta=best[3];anchor=origin+delta
    descriptors=[]
    for i,(block,pos,size) in enumerate(parts):
        descriptors.append(dict(id=prop['id']+':part:'+str(i),sourceInstance=prop['id'],chapter=index,
            uuid=str(uuid.uuid5(uuid.NAMESPACE_URL,'muxi-outbreak/lostschool/'+prop['id']+'/'+str(i))),position=np.round(anchor,6).tolist(),block=block,
            translation=np.round(rotate(q,pos*scale),6).tolist(),rotation=np.round(q,8).tolist(),scale=np.round(size*scale,6).tolist()))
    return dict(prior,status='placed_display' if best[1]>=.25 else 'manual_review',reason=None if best[1]>=.25 else 'display_mostly_occluded_after_bounded_pivot_review',
                firstPassExclusion=prior.get('reason'),placementKind='collision_free_display',nativeAnchor=np.floor(anchor).astype(int).tolist(),nativeDisplayOrigin=np.round(anchor,6).tolist(),
                sourcePoseRetained=True,fullSourceAnglesRetained=True,renderPivotOffset=np.round(delta,6).tolist(),visibilitySampleFraction=round(best[1],4),
                pivotInference=True,nativeApproximation=True,physics='static cosmetic approximation; Source movable prop physics is not reproduced; no collision/inventory/projectile target',
                manualModelPivotReview=kind=='fixture' and prop['model'].endswith('/wall_light.mdl'),
                descriptors=descriptors if best[1]>=.25 else [],reviewCandidateDescriptors=descriptors if best[1]<.25 else [])
