import os, cv2, numpy as np
from flask import Flask, request, jsonify

app = Flask(__name__)
DATA = os.path.join(os.path.dirname(__file__), 'faces')
os.makedirs(DATA, exist_ok=True)
CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')

def load_image(file):
    raw = np.frombuffer(file.read(), np.uint8)
    img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
    return img

def crop_face(img):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    faces = CASCADE.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(80,80))
    if len(faces) != 1:
        return None, len(faces)
    x,y,w,h = faces[0]
    return gray[y:y+h, x:x+w], 1

def orb_similarity(a,b):
    orb=cv2.ORB_create(nfeatures=600)
    k1,d1=orb.detectAndCompute(a,None); k2,d2=orb.detectAndCompute(b,None)
    if d1 is None or d2 is None: return 0.0
    matcher=cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    matches=matcher.match(d1,d2)
    if not matches:return 0.0
    good=[m for m in matches if m.distance < 60]
    return len(good)/max(1,min(len(k1),len(k2)))

@app.get('/health')
def health(): return jsonify({'status':'UP','service':'opencv-face-service'})

@app.post('/register')
def register():
    sid=request.form.get('studentId')
    img=load_image(request.files['image'])
    face,count=crop_face(img)
    if face is None:return jsonify({'registered':False,'message':f'Expected exactly one face, found {count}'}),400
    cv2.imwrite(os.path.join(DATA,f'{sid}.png'),face)
    return jsonify({'registered':True,'studentId':sid})

@app.post('/verify')
def verify():
    sid=request.form.get('studentId')
    path=os.path.join(DATA,f'{sid}.png')
    if not os.path.exists(path):return jsonify({'verified':False,'message':'Face is not registered'}),404
    img=load_image(request.files['image'])
    face,count=crop_face(img)
    if face is None:return jsonify({'verified':False,'message':f'Expected exactly one face, found {count}'}),400
    ref=cv2.imread(path,cv2.IMREAD_GRAYSCALE)
    score=orb_similarity(ref,face)
    # Demo threshold. This is NOT production biometric authentication.
    verified=score >= 0.12
    return jsonify({'verified':verified,'studentId':sid,'similarity':round(score,3),'message':'Face verified' if verified else 'Face not matched'})

if __name__=='__main__': app.run(host='0.0.0.0',port=5001,debug=True)
