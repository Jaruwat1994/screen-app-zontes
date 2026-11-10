# ZRide Map Server (ประเทศไทย)

เซิร์ฟเวอร์ของเราเองสำหรับ**คำนวณเส้นทางมอเตอร์ไซค์** (Valhalla) และ**ค้นหาสถานที่** (Nominatim + Photon)
แทนเซิร์ฟเวอร์สาธารณะฟรีที่มีเงื่อนไขห้ามใช้หนักหรือใช้เชิงพาณิชย์ ดูเหตุผลใน `docs/BUSINESS-PLAN-TH.md` ข้อ 2.1

แอปเรียกใช้ผ่านที่อยู่เดียว (`MapServer.kt`):

| เส้นทาง | บริการ | ใช้ทำอะไรในแอป |
|---|---|---|
| `https://<โดเมน>/route` | Valhalla | เส้นทางมอเตอร์ไซค์ เส้นทางโค้งสวย นำทางบนหน้าปัด |
| `https://<โดเมน>/nominatim/search`, `/nominatim/reverse` | Nominatim | ค้นหาสถานที่ ชื่อถนนบนหน้าปัด ชื่อทริป |
| `https://<โดเมน>/photon/api`, `/photon/reverse` | Photon | ค้นหาแบบพิมพ์แล้วขึ้นผล ปั๊มและสถานที่ใกล้ตัว |

ภาพแผนที่ยังใช้ OpenFreeMap (อนุญาตเชิงพาณิชย์ ต้องให้เครดิต OSM ซึ่งแอปแสดงแล้ว)
ส่วน Overpass (ไฟจราจร ข้อมูลความเร็ว) ยังใช้เซิร์ฟเวอร์สาธารณะ จะย้ายในขั้นถัดไป

## เครื่องที่ต้องใช้
- VPS Linux (Ubuntu 22.04/24.04) **RAM 16 GB** (8 GB ใช้ได้ถ้า import Nominatim ช้าลง) ดิสก์ SSD **80 GB ขึ้นไป** CPU 4 คอร์
- โดเมนหรือ subdomain เช่น `map.zride.app` ชี้ (A record) มาที่ IP ของ VPS
- เปิดพอร์ต 80 และ 443

## ติดตั้ง
```bash
# บน VPS
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
git clone <repo ของเรา> && cd <repo>/server
cp .env.example .env && nano .env        # ใส่ DOMAIN และรหัสผ่าน Nominatim
sudo docker compose up -d
sudo docker compose logs -f valhalla nominatim photon   # ดูความคืบหน้า import ครั้งแรก
```
ครั้งแรกใช้เวลาประมาณ 1–3 ชั่วโมง (Nominatim นานที่สุด) เสร็จแล้วทดสอบ:
```bash
curl https://<โดเมน>/status
curl "https://<โดเมน>/nominatim/search?q=สยามพารากอน&format=jsonv2"
curl "https://<โดเมน>/photon/api/?q=ปตท&limit=3"
```

## ให้แอปใช้เซิร์ฟเวอร์นี้
build ด้วย `-PmapServer=https://<โดเมน>` หรือใส่ `mapServer=https://<โดเมน>` ใน `~/.gradle/gradle.properties`
แอปที่ build แบบไม่ใส่ค่านี้จะใช้เซิร์ฟเวอร์สาธารณะ (ใช้ทดสอบเท่านั้น)

## ดูแล
- ข้อมูล OSM อัปเดต: Nominatim อัปเดตเองจาก Geofabrik (REPLICATION_URL), Photon ตาม UPDATE_INTERVAL,
  Valhalla สั่ง build ใหม่สัปดาห์ละครั้ง: `docker compose restart valhalla` (ตั้ง force_rebuild ตาม README ของ image)
- สำรอง: ไม่จำเป็น ข้อมูลทั้งหมดสร้างใหม่จาก OSM ได้
- ไม่เปิด access log (Caddyfile) เพราะคำขอเส้นทางมีจุดเริ่มและปลายทางของผู้ใช้ (PDPA)
- ⚠️ ชื่อ image และตัวแปร environment ของ Valhalla / Nominatim / Photon เปลี่ยนตามเวอร์ชัน
  ตรวจ README ของแต่ละ image ก่อน import ครั้งแรก

## สัญญาอนุญาตของซอฟต์แวร์เซิร์ฟเวอร์
Valhalla (MIT), Nominatim (GPL‑2.0), Photon (Apache‑2.0), Caddy (Apache‑2.0) รันตามที่เป็น ไม่ได้แก้โค้ด
ข้อมูล © OpenStreetMap contributors (ODbL) ⚖️ ตรวจเงื่อนไขอีกครั้งก่อนเปิดบริการเชิงพาณิชย์
