window.RECEIPTOCR_CONFIG = {
  API_BASE_URL: "https://receiptocr-api.somchai147258.workers.dev"
};

/*
 * Round76: BILL_DATE ต้องกำหนดรูปแบบจาก Admin ไม่ให้ APK เดาลำดับวัน/เดือนเอง
 * ใช้ select เดิมในหน้า OCR เพื่อไม่เพิ่มหน้าจอเทคนิคใหม่
 */
(function(){
  const DATE_FORMATS = [
    ["DD/MM/YYYY", "วัน/เดือน/ปี — dd/MM/yyyy"],
    ["MM/DD/YYYY", "เดือน/วัน/ปี — MM/dd/yyyy"]
  ];

  function syncReceiptDateFormatEditor(){
    const title = document.getElementById("fieldTitle");
    const select = document.getElementById("fieldFormat");
    const settings = document.getElementById("fieldSettings");
    if(!title || !select || !settings || settings.classList.contains("hidden")) return;

    const isDate = title.textContent.trim() === "วันที่ในบิล";
    if(!isDate){
      select.disabled = true;
      return;
    }

    const previous = select.value;
    const exact = DATE_FORMATS.some(([value]) => value === previous) ? previous : "DD/MM/YYYY";
    select.innerHTML = DATE_FORMATS.map(([value,label]) =>
      `<option value="${value}">${label}</option>`
    ).join("");
    select.disabled = false;
    select.value = exact;

    // ข้อมูลเดิมใช้ค่า DATE แบบกว้าง เมื่อเปิดแก้ไขครั้งแรกให้เปลี่ยนเป็น DMY ชัดเจน
    // ผ่าน change handler เดิมของ ocr-simple.js เพื่อบันทึกลง template จริง
    if(previous === "DATE" || previous === "ANY" || !previous){
      select.dispatchEvent(new Event("change", {bubbles:true}));
    }
  }

  document.addEventListener("click", () => setTimeout(syncReceiptDateFormatEditor, 0));
  document.addEventListener("DOMContentLoaded", () => setTimeout(syncReceiptDateFormatEditor, 0));
})();
