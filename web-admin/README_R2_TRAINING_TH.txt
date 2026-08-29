Round21 - Training Image Flow

หน้า Admin:
1. เลือกภาพตัวอย่างบิล
2. วาด ROI และตั้ง field/rule
3. กรอกชื่อชุดตัวอย่าง/หมายเหตุ
4. กด "อัปโหลดภาพ + บันทึกตัวอย่าง"

ระบบจะ:
- Upload image -> Cloudflare R2
- Save imageKey + annotations -> D1 ocr_training_examples

Worker endpoints:
POST /api/training-images
GET  /api/training-images/{imageKey}
POST /api/training-examples
